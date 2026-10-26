package com.example.timescapedemo

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Lifecycle bridge for Samsung's proprietary S Pen Remote SDK.
 *
 * Reflection keeps non-Samsung builds runnable when the optional official AAR is not installed.
 * When the official `penremote-v1.0.0` AAR/JAR is present, the bridge connects to the SDK, registers the
 * button unit listener, and forwards ACTION_DOWN events.
 */
class SamsungSpenRemoteBridge(
    context: Context,
    private val onButtonPressed: () -> Unit
) {
    private val appContext = context.applicationContext
    private var remote: Any? = null
    private var unitManager: Any? = null
    private var buttonUnit: Any? = null
    private var buttonListener: Any? = null
    private var connecting = false

    fun connect() {
        if (connecting || unitManager != null) return
        val remoteClass = runCatching {
            Class.forName("com.samsung.android.sdk.penremote.SpenRemote")
        }.getOrNull() ?: return
        val callbackClass = remoteClass.declaredClasses.firstOrNull {
            it.simpleName == "ConnectionResultCallback"
        } ?: return
        val instance = runCatching { remoteClass.getMethod("getInstance").invoke(null) }.getOrNull()
            ?: return
        remote = instance
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, method, args ->
            when (method.name) {
                "onSuccess" -> args?.firstOrNull()?.let(::registerButtonListener)
                "onFailure" -> {
                    connecting = false
                    Log.w(TAG, "S Pen Remote SDK connection failed: ${args?.joinToString()}")
                }
            }
            null
        }
        val connectMethod = remoteClass.methods.firstOrNull { it.name == "connect" } ?: return
        connecting = true
        runCatching {
            when (connectMethod.parameterTypes.size) {
                2 -> connectMethod.invoke(instance, appContext, callback)
                1 -> connectMethod.invoke(instance, callback)
                else -> error("Unsupported S Pen Remote connect signature")
            }
        }.onFailure {
            connecting = false
            Log.w(TAG, "Unable to connect to S Pen Remote SDK", it)
        }
    }

    fun disconnect() {
        val manager = unitManager
        val unit = buttonUnit
        val listener = buttonListener
        if (manager != null && unit != null && listener != null) {
            manager.javaClass.methods.firstOrNull { it.name == "unregisterSpenEventListener" }
                ?.let { method ->
                    runCatching {
                        if (method.parameterTypes.size == 2) method.invoke(manager, listener, unit)
                        else method.invoke(manager, unit)
                    }
                }
        }
        remote?.let { instance ->
            instance.javaClass.methods.firstOrNull { it.name == "disconnect" }?.let { method ->
                runCatching {
                    if (method.parameterTypes.isEmpty()) method.invoke(instance)
                    else method.invoke(instance, appContext)
                }
            }
        }
        connecting = false
        unitManager = null
        buttonUnit = null
        buttonListener = null
        remote = null
    }

    private fun registerButtonListener(manager: Any) {
        connecting = false
        unitManager = manager
        val unitClass = runCatching {
            Class.forName("com.samsung.android.sdk.penremote.SpenUnit")
        }.getOrNull() ?: return
        val typeButton = runCatching { unitClass.getField("TYPE_BUTTON").getInt(null) }.getOrNull()
            ?: return
        val unit = manager.javaClass.getMethod("getUnit", Integer.TYPE)
            .invoke(manager, typeButton) ?: return
        buttonUnit = unit
        val listenerClass = sequenceOf(
            "com.samsung.android.sdk.penremote.ButtonEventListener",
            "com.samsung.android.sdk.penremote.button.ButtonEventListener"
        ).mapNotNull { runCatching { Class.forName(it) }.getOrNull() }.firstOrNull() ?: return
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            if (method.name == "onEvent") {
                val event = args?.firstOrNull()
                val action = event?.javaClass?.getMethod("getAction")?.invoke(event) as? Int
                val actionDown = event?.javaClass?.getField("ACTION_DOWN")?.getInt(null)
                if (action != null && action == actionDown) onButtonPressed()
            }
            null
        }
        buttonListener = listener
        runCatching {
            manager.javaClass.methods.firstOrNull { it.name == "registerSpenEventListener" }
                ?.invoke(manager, listener, unit)
        }.onFailure { Log.w(TAG, "Unable to register S Pen Remote button listener", it) }
    }

    private companion object {
        const val TAG = "SamsungSpenRemote"
    }
}
