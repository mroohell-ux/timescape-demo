package com.example.timescapedemo

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TimescapeLanServer(
    context: Context,
    private val exportPayloadProvider: () -> String,
    private val appLabelProvider: () -> String
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val tokens = ConcurrentHashMap<String, String>()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var httpServer: NanoHTTPD? = null
    var port: Int = -1
        private set

    fun start() {
        if (httpServer != null) return
        val server = object : NanoHTTPD("0.0.0.0", 0) {
            override fun serve(session: IHTTPSession): Response {
                return handleRequest(session)
            }
        }
        try {
            server.start(SOCKET_READ_TIMEOUT, false)
        } catch (e: IOException) {
            return
        }
        httpServer = server
        port = server.listeningPort
        registerNsd()
    }

    fun stop() {
        unregisterNsd()
        httpServer?.stop()
        httpServer = null
        port = -1
        sessions.clear()
        tokens.clear()
    }

    private fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = session.uri.orEmpty()
        return when {
            session.method == NanoHTTPD.Method.GET && path == "/meta" -> {
                jsonOk(
                    JSONObject().apply {
                        put("name", appLabelProvider())
                        put("serviceType", SERVICE_TYPE)
                        put("port", port)
                    }
                )
            }

            session.method == NanoHTTPD.Method.POST && path == "/session/request" -> {
                val body = readPostBody(session)
                val request = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
                val clientId = request.optString("clientId")
                val clientName = request.optString("clientName")
                val sessionId = UUID.randomUUID().toString()
                sessions[sessionId] = SessionState(
                    sessionId = sessionId,
                    clientId = clientId,
                    clientName = clientName,
                    status = SessionStatus.PENDING
                )
                mainHandler.postDelayed({ approve(sessionId) }, AUTO_APPROVE_DELAY_MS)
                jsonOk(JSONObject().put("sessionId", sessionId))
            }

            session.method == NanoHTTPD.Method.GET && path == "/session/status" -> {
                val sessionId = session.parameters["sessionId"]?.firstOrNull().orEmpty()
                val state = sessions[sessionId]
                if (state == null) {
                    jsonError(NanoHTTPD.Response.Status.NOT_FOUND, "unknown_session")
                } else {
                    when (state.status) {
                        SessionStatus.PENDING -> jsonOk(JSONObject().put("status", "PENDING"))
                        SessionStatus.DENIED -> jsonOk(JSONObject().put("status", "DENIED"))
                        SessionStatus.APPROVED -> jsonOk(
                            JSONObject().apply {
                                put("status", "APPROVED")
                                put("token", state.token)
                            }
                        )
                    }
                }
            }

            session.method == NanoHTTPD.Method.GET && path == "/export" -> {
                val token = session.parameters["token"]?.firstOrNull().orEmpty()
                if (token.isBlank() || !tokens.containsKey(token)) {
                    jsonError(NanoHTTPD.Response.Status.FORBIDDEN, "invalid_token")
                } else {
                    val payload = runCatching { exportPayloadProvider() }
                        .getOrElse {
                            return jsonError(NanoHTTPD.Response.Status.INTERNAL_ERROR, "export_failed")
                        }
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/json; charset=utf-8",
                        payload
                    )
                }
            }

            else -> jsonError(NanoHTTPD.Response.Status.NOT_FOUND, "not_found")
        }
    }

    private fun approve(sessionId: String) {
        val state = sessions[sessionId] ?: return
        if (state.status != SessionStatus.PENDING) return
        val token = "tok_${UUID.randomUUID().toString().replace("-", "")}" // keep URL safe
        sessions[sessionId] = state.copy(status = SessionStatus.APPROVED, token = token)
        tokens[token] = sessionId
    }

    private fun readPostBody(session: NanoHTTPD.IHTTPSession): String {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun registerNsd() {
        val manager = nsdManager ?: return
        if (port <= 0) return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = DEFAULT_SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterNsd() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        runCatching { manager.unregisterService(listener) }
        registrationListener = null
    }

    private fun jsonOk(body: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", body.toString())

    private fun jsonError(status: NanoHTTPD.Response.Status, code: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            JSONObject().put("error", code).toString()
        )

    private data class SessionState(
        val sessionId: String,
        val clientId: String,
        val clientName: String,
        val status: SessionStatus,
        val token: String? = null
    )

    private enum class SessionStatus { PENDING, APPROVED, DENIED }

    companion object {
        private const val SERVICE_TYPE = "_timescape._tcp"
        private const val DEFAULT_SERVICE_NAME = "Timescape"
        private const val AUTO_APPROVE_DELAY_MS = 800L
    }
}
