package com.example.timescapedemo

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalSyncServer(
    private val context: Context,
    private val exportProvider: () -> String,
    private val onStatusChanged: (LocalSyncStatus) -> Unit,
    private val onRequestsChanged: (List<LocalSyncRequest>) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var timeoutJob: Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val requests = ConcurrentHashMap<String, Session>()

    fun start() {
        if (serverSocket != null) return
        requests.clear()
        onRequestsChanged(emptyList())
        scope.launch {
            runCatching {
                val socket = ServerSocket(0)
                serverSocket = socket
                val serviceName = "Timescape on ${deviceName()}"
                registerNsd(socket.localPort, serviceName)
                onStatusChanged(LocalSyncStatus.Advertising(serviceName, socket.localPort))
                startTimeoutCountdown()
                acceptJob = launch { acceptLoop(socket) }
            }.onFailure {
                Log.e(TAG, "Failed to start local sync server", it)
                onStatusChanged(LocalSyncStatus.Error(it.message ?: "Failed to start local sync"))
                stop()
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        serverSocket?.closeQuietly()
        serverSocket = null
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        requests.clear()
        onRequestsChanged(emptyList())
        onStatusChanged(LocalSyncStatus.Stopped)
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun decide(sessionId: String, approve: Boolean): LocalSyncDecisionResult {
        val session = requests[sessionId] ?: return LocalSyncDecisionResult.NotFound
        val now = SystemClock.elapsedRealtime()
        if (approve) {
            val token = UUID.randomUUID().toString().replace("-", "")
            session.status = SessionStatus.APPROVED
            session.token = token
            session.expiresAt = now + TOKEN_TTL_MS
            emitRequests()
            return LocalSyncDecisionResult.Decided(session.status.name, token, TOKEN_TTL_SEC)
        }
        session.status = SessionStatus.DENIED
        session.token = null
        session.expiresAt = null
        emitRequests()
        return LocalSyncDecisionResult.Decided(session.status.name, null, null)
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            runCatching { socket.accept() }
                .onSuccess { client ->
                    scope.launch {
                        handleClient(client)
                    }
                }
                .onFailure {
                    if (!socket.isClosed) {
                        Log.w(TAG, "Accept loop failed", it)
                    }
                }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeJson(output, 400, jsonOf("error" to "Malformed request"))
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val rawPath = parts[1]
            val (path, query) = splitPathAndQuery(rawPath)
            val headers = readHeaders(input)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) readBody(input, contentLength) else ""
            val response = route(method, path, query, body)
            if (response.contentType == "application/json") {
                writeJson(output, response.statusCode, response.body)
            } else {
                writeText(output, response.statusCode, response.body, response.contentType)
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String
    ): HttpResponse {
        return when {
            method == "GET" && path == "/meta" -> {
                HttpResponse(200, "application/json", jsonOf(
                    "app" to "Timescape",
                    "protocolVersion" to 1,
                    "deviceName" to deviceName()
                ))
            }

            method == "POST" && path == "/session/request" -> {
                val requestObj = runCatching { JSONObject(body) }.getOrNull()
                    ?: return HttpResponse(400, "application/json", jsonOf("error" to "Invalid JSON"))
                val clientId = requestObj.optString("clientId")
                val clientName = requestObj.optString("clientName")
                if (clientId.isBlank() || clientName.isBlank()) {
                    return HttpResponse(400, "application/json", jsonOf("error" to "clientId and clientName are required"))
                }
                val sessionId = UUID.randomUUID().toString()
                requests[sessionId] = Session(sessionId, clientId, clientName)
                emitRequests()
                HttpResponse(200, "application/json", jsonOf("sessionId" to sessionId, "status" to SessionStatus.PENDING.name))
            }

            method == "GET" && path == "/session/status" -> {
                val sessionId = query["sessionId"] ?: ""
                val session = requests[sessionId]
                    ?: return HttpResponse(404, "application/json", jsonOf("error" to "Unknown sessionId"))
                val payload = JSONObject().apply {
                    put("status", session.status.name)
                    if (session.status == SessionStatus.APPROVED && !session.isExpired()) {
                        put("token", session.token)
                        put("expiresInSec", session.remainingSeconds())
                    }
                }
                HttpResponse(200, "application/json", payload)
            }

            method == "POST" && path == "/session/decision" -> {
                val requestObj = runCatching { JSONObject(body) }.getOrNull()
                    ?: return HttpResponse(400, "application/json", jsonOf("error" to "Invalid JSON"))
                val sessionId = requestObj.optString("sessionId")
                val approve = requestObj.optBoolean("approve", false)
                when (val result = decide(sessionId, approve)) {
                    LocalSyncDecisionResult.NotFound -> HttpResponse(404, "application/json", jsonOf("error" to "Unknown sessionId"))
                    is LocalSyncDecisionResult.Decided -> {
                        HttpResponse(200, "application/json", JSONObject().apply {
                            put("status", result.status)
                            result.token?.let { put("token", it) }
                            result.expiresInSec?.let { put("expiresInSec", it) }
                        })
                    }
                }
            }

            method == "GET" && path == "/export" -> {
                val token = query["token"] ?: ""
                val session = requests.values.firstOrNull { it.token == token && it.status == SessionStatus.APPROVED }
                    ?: return HttpResponse(401, "application/json", jsonOf("error" to "Invalid token"))
                if (session.isExpired()) {
                    return HttpResponse(401, "application/json", jsonOf("error" to "Token expired"))
                }
                HttpResponse(200, "application/json", exportProvider())
            }

            else -> HttpResponse(404, "application/json", jsonOf("error" to "Not found"))
        }
    }

    private fun emitRequests() {
        onRequestsChanged(
            requests.values
                .sortedByDescending { it.createdAt }
                .map {
                    LocalSyncRequest(
                        sessionId = it.sessionId,
                        clientId = it.clientId,
                        clientName = it.clientName,
                        status = it.status.name
                    )
                }
        )
    }

    private fun startTimeoutCountdown() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(SERVER_TIMEOUT_MS)
            stop()
        }
    }

    private fun registerNsd(port: Int, preferredName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = preferredName
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                onStatusChanged(LocalSyncStatus.Advertising(serviceInfo.serviceName, port))
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onStatusChanged(LocalSyncStatus.Error("NSD registration failed ($errorCode)"))
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun deviceName(): String {
        return Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    }

    private fun splitPathAndQuery(path: String): Pair<String, Map<String, String>> {
        val chunks = path.split("?", limit = 2)
        val endpoint = chunks[0]
        val queryMap = mutableMapOf<String, String>()
        if (chunks.size > 1) {
            chunks[1].split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.isNotEmpty() && kv[0].isNotBlank()) {
                    queryMap[urlDecode(kv[0])] = if (kv.size > 1) urlDecode(kv[1]) else ""
                }
            }
        }
        return endpoint to queryMap
    }

    private fun urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase(Locale.US)
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun readBody(input: BufferedInputStream, contentLength: Int): String {
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(bytes, read, contentLength - read)
            if (count <= 0) break
            read += count
        }
        return String(bytes, 0, read, StandardCharsets.UTF_8)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        var prev = -1
        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (builder.isEmpty()) null else builder.toString()
            }
            if (prev == '\r'.code && value == '\n'.code) {
                builder.setLength(builder.length - 1)
                return builder.toString()
            }
            builder.append(value.toChar())
            prev = value
        }
    }

    private fun writeJson(output: BufferedOutputStream, statusCode: Int, payload: Any) {
        writeText(output, statusCode, payload.toString(), "application/json")
    }

    private fun writeText(output: BufferedOutputStream, statusCode: Int, body: String, contentType: String) {
        val data = body.toByteArray(StandardCharsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 $statusCode ${reason(statusCode)}\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${data.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        output.write(response)
        output.write(data)
        output.flush()
    }

    private fun jsonOf(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        for ((key, value) in pairs) {
            put(key, value)
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun ServerSocket.closeQuietly() {
        runCatching { close() }
    }

    private data class HttpResponse(val statusCode: Int, val contentType: String, val body: Any)

    private data class Session(
        val sessionId: String,
        val clientId: String,
        val clientName: String,
        val createdAt: Long = SystemClock.elapsedRealtime(),
        var status: SessionStatus = SessionStatus.PENDING,
        var token: String? = null,
        var expiresAt: Long? = null
    ) {
        fun isExpired(now: Long = SystemClock.elapsedRealtime()): Boolean {
            val target = expiresAt ?: return false
            return now >= target
        }

        fun remainingSeconds(now: Long = SystemClock.elapsedRealtime()): Int {
            val target = expiresAt ?: return 0
            return ((target - now).coerceAtLeast(0L) / 1000L).toInt()
        }
    }

    private enum class SessionStatus {
        PENDING,
        APPROVED,
        DENIED
    }

    sealed interface LocalSyncDecisionResult {
        data object NotFound : LocalSyncDecisionResult
        data class Decided(val status: String, val token: String?, val expiresInSec: Int?) : LocalSyncDecisionResult
    }

    sealed interface LocalSyncStatus {
        data object Stopped : LocalSyncStatus
        data class Advertising(val serviceName: String, val port: Int) : LocalSyncStatus
        data class Error(val message: String) : LocalSyncStatus
    }

    data class LocalSyncRequest(
        val sessionId: String,
        val clientId: String,
        val clientName: String,
        val status: String
    )

    companion object {
        private const val TAG = "LocalSyncServer"
        private const val SERVICE_TYPE = "_timescape._tcp"
        private const val TOKEN_TTL_SEC = 120
        private const val TOKEN_TTL_MS = TOKEN_TTL_SEC * 1000L
        private const val SERVER_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
