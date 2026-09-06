package com.example.runner.backend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import com.example.runner.model.BackendProcessStatus
import com.example.runner.python.PyCallable
import com.example.runner.python.PythonRuntime
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Real separate Android OS process for Runner-managed Python backend runtime.
 * Runs in process ":python_backend".
 * Communicates via native Android Binder IPC (Messenger) without HTTP servers or network ports.
 */
class PythonBackendService : Service() {

    companion object {
        const val CHANNEL_ID = "runner_backend_channel"
        const val NOTIFICATION_ID = 1001

        const val MSG_REGISTER_CLIENT = 1
        const val MSG_UNREGISTER_CLIENT = 2
        const val MSG_START_BACKEND = 10
        const val MSG_STOP_BACKEND = 11
        const val MSG_INVOKE_BRIDGE = 20
        const val MSG_BRIDGE_RESPONSE = 21
        const val MSG_BACKEND_LOG = 30
        const val MSG_BACKEND_STATUS = 31

        const val KEY_PROJECT_DIR = "project_dir"
        const val KEY_BACKEND_FILE = "backend_file"
        const val KEY_PIP_DIR = "pip_dir"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ACTION = "action"
        const val KEY_PAYLOAD = "payload"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_TEXT = "text"
        const val KEY_IS_ERROR = "is_error"
        const val KEY_STATUS = "status"
    }

    private val executor = Executors.newCachedThreadPool()
    private var clientMessenger: Messenger? = null
    private var serviceMessenger: Messenger? = null

    private var currentRuntime: PythonRuntime? = null
    private var currentSessionId: String = ""
    private var status: BackendProcessStatus = BackendProcessStatus.STOPPED

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceMessenger = Messenger(IncomingHandler(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Run as foreground service to respect Android background execution rules (Section 17 & 37)
        val notification = buildNotification("Runner Backend Running")
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return serviceMessenger?.binder
    }

    override fun onDestroy() {
        stopCurrentBackend()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Runner Backend Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Manages background Python runtime and TeleBot processes"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Runner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private class IncomingHandler(private val service: PythonBackendService) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_REGISTER_CLIENT -> {
                    service.clientMessenger = msg.replyTo
                }
                MSG_UNREGISTER_CLIENT -> {
                    if (service.clientMessenger == msg.replyTo) {
                        service.clientMessenger = null
                    }
                }
                MSG_START_BACKEND -> {
                    val data = msg.data
                    val projectDirPath = data.getString(KEY_PROJECT_DIR) ?: ""
                    val backendFileName = data.getString(KEY_BACKEND_FILE) ?: "app2.py"
                    val pipDirPath = data.getString(KEY_PIP_DIR) ?: ""
                    val sessionId = data.getString(KEY_SESSION_ID) ?: ""
                    service.startBackend(projectDirPath, backendFileName, pipDirPath, sessionId)
                }
                MSG_STOP_BACKEND -> {
                    val sessionId = msg.data.getString(KEY_SESSION_ID) ?: ""
                    if (sessionId.isEmpty() || sessionId == service.currentSessionId) {
                        service.stopCurrentBackend()
                    }
                }
                MSG_INVOKE_BRIDGE -> {
                    val data = msg.data
                    val action = data.getString(KEY_ACTION) ?: ""
                    val payload = data.getString(KEY_PAYLOAD) ?: ""
                    val requestId = data.getString(KEY_REQUEST_ID) ?: ""
                    val sessionId = data.getString(KEY_SESSION_ID) ?: ""
                    service.handleBridgeInvocation(action, payload, requestId, sessionId)
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private fun startBackend(projectDirPath: String, backendFileName: String, pipDirPath: String, sessionId: String) {
        // Prevent duplicate app2.py processes (Section 14 & 36)
        stopCurrentBackend()

        currentSessionId = sessionId
        status = BackendProcessStatus.STARTING
        sendStatus(status, sessionId)
        sendLog("[Process :python_backend] Starting app2.py runtime (PID: ${android.os.Process.myPid()})...")

        val projectDir = File(projectDirPath)
        val pipDir = File(pipDirPath)
        val backendFile = File(projectDir, backendFileName)

        executor.execute {
            try {
                val runtime = PythonRuntime(
                    workingDir = projectDir,
                    pipPackagesDir = pipDir,
                    onOutput = { text -> sendLog(text, isError = false) },
                    onError = { text -> sendLog(text, isError = true) }
                )
                currentRuntime = runtime
                status = BackendProcessStatus.RUNNING
                sendStatus(status, sessionId)

                if (backendFile.exists()) {
                    sendLog("[Process :python_backend] Executing $backendFileName...")
                    val exitCode = runtime.executeFile(backendFile)
                    if (exitCode != 0) {
                        sendLog("[Process :python_backend] Backend exited with code $exitCode", isError = true)
                    }
                } else {
                    sendLog("[Process :python_backend] Backend file not found: ${backendFile.name}", isError = true)
                    status = BackendProcessStatus.ERROR
                    sendStatus(status, sessionId)
                }
            } catch (e: Exception) {
                sendLog("[Process :python_backend] Backend error: ${e.message}", isError = true)
                status = BackendProcessStatus.ERROR
                sendStatus(status, sessionId)
            }
        }
    }

    private fun stopCurrentBackend() {
        currentRuntime?.stop()
        currentRuntime = null
        status = BackendProcessStatus.STOPPED
        sendStatus(status, currentSessionId)
        sendLog("[Process :python_backend] Backend stopped")
    }

    private fun handleBridgeInvocation(action: String, payload: String, requestId: String, sessionId: String) {
        // Verify session ID to avoid stale requests
        if (sessionId.isNotEmpty() && sessionId != currentSessionId) {
            sendBridgeResponse(
                requestId = requestId,
                result = null,
                error = "Stale backend session request rejected",
                sessionId = sessionId
            )
            return
        }

        val runtime = currentRuntime
        if (runtime == null || status != BackendProcessStatus.RUNNING) {
            sendBridgeResponse(
                requestId = requestId,
                result = null,
                error = "Python backend is not running (status: $status)",
                sessionId = sessionId
            )
            return
        }

        executor.execute {
            try {
                sendLog("[Bridge Call] action='$action', payload='$payload'")

                // 1. If action is sendMessage or related to TeleBot
                if ((action == "sendMessage" || action == "send_message") && runtime.activeBots.isNotEmpty()) {
                    val bot = runtime.activeBots.first()
                    // If payload is JSON with chat_id and text
                    val (chatId, text) = parseMessagePayload(payload)
                    val resp = bot.sendMessage(chatId, text)
                    sendBridgeResponse(requestId, resp.toString(), null, sessionId)
                    return@execute
                }

                // 2. Check if a function is exported in globals
                val func = runtime.globals[action] as? PyCallable
                if (func != null) {
                    val args = parseArgsFromPayload(payload)
                    val result = func.call(args, emptyMap())
                    val resultStr = when (result) {
                        is JSONObject -> result.toString()
                        is Map<*, *> -> JSONObject(result).toString()
                        else -> result?.toString() ?: "ok"
                    }
                    sendBridgeResponse(requestId, resultStr, null, sessionId)
                    return@execute
                }

                // 3. Fallback: evaluate expression or statement in runtime
                val evalRes = runtime.evalExpression(action)
                sendBridgeResponse(requestId, evalRes?.toString() ?: "ok", null, sessionId)

            } catch (e: Throwable) {
                sendLog("[Bridge Error] ${e.message}", isError = true)
                sendBridgeResponse(requestId, null, e.message ?: "Unknown backend error", sessionId)
            }
        }
    }

    private fun parseMessagePayload(payload: String): Pair<Any, String> {
        return try {
            if (payload.trim().startsWith("{")) {
                val json = JSONObject(payload)
                val chatId = json.opt("chat_id") ?: json.opt("chatId") ?: "0"
                val text = json.optString("text", payload)
                Pair(chatId, text)
            } else if (payload.trim().startsWith("[")) {
                val arr = org.json.JSONArray(payload)
                val first = arr.opt(0) ?: ""
                val second = arr.opt(1) ?: ""
                if (arr.length() >= 2) Pair(first, second.toString()) else Pair("default", first.toString())
            } else {
                Pair("default", payload)
            }
        } catch (_: Exception) {
            Pair("default", payload)
        }
    }

    private fun parseArgsFromPayload(payload: String): List<Any?> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                List(arr.length()) { arr.get(it) }
            } else if (trimmed.startsWith("{")) {
                listOf(JSONObject(trimmed))
            } else {
                listOf(payload)
            }
        } catch (_: Exception) {
            listOf(payload)
        }
    }

    private fun sendLog(text: String, isError: Boolean = false) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, MSG_BACKEND_LOG).apply {
                data = Bundle().apply {
                    putString(KEY_TEXT, text)
                    putBoolean(KEY_IS_ERROR, isError)
                }
            }
            messenger.send(msg)
        } catch (_: RemoteException) {}
    }

    private fun sendStatus(newStatus: BackendProcessStatus, sessionId: String) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, MSG_BACKEND_STATUS).apply {
                data = Bundle().apply {
                    putString(KEY_STATUS, newStatus.name)
                    putString(KEY_SESSION_ID, sessionId)
                }
            }
            messenger.send(msg)
        } catch (_: RemoteException) {}
    }

    private fun sendBridgeResponse(requestId: String, result: String?, error: String?, sessionId: String) {
        val messenger = clientMessenger ?: return
        try {
            val msg = Message.obtain(null, MSG_BRIDGE_RESPONSE).apply {
                data = Bundle().apply {
                    putString(KEY_REQUEST_ID, requestId)
                    putString(KEY_RESULT, result)
                    putString(KEY_ERROR, error)
                    putString(KEY_SESSION_ID, sessionId)
                }
            }
            messenger.send(msg)
        } catch (_: RemoteException) {}
    }
}
