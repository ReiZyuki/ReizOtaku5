package com.example.runner.backend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.example.runner.model.BackendProcessStatus
import com.example.runner.model.TerminalLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BackendClientManager(private val context: Context) {

    private var serviceMessenger: Messenger? = null
    private val clientMessenger = Messenger(ClientIncomingHandler())
    private var isBound = false

    private var currentSessionId = UUID.randomUUID().toString()

    private val _status = MutableStateFlow(BackendProcessStatus.STOPPED)
    val status: StateFlow<BackendProcessStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<TerminalLine>>(emptyList())
    val logs: StateFlow<List<TerminalLine>> = _logs.asStateFlow()

    private val pendingRequests = ConcurrentHashMap<String, (result: String?, error: String?) -> Unit>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = Messenger(service)
            isBound = true
            try {
                val msg = Message.obtain(null, PythonBackendService.MSG_REGISTER_CLIENT).apply {
                    replyTo = clientMessenger
                }
                serviceMessenger?.send(msg)
            } catch (_: RemoteException) {}
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            isBound = false
            _status.value = BackendProcessStatus.STOPPED
        }
    }

    init {
        bindService()
    }

    fun bindService() {
        if (!isBound) {
            val intent = Intent(context, PythonBackendService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService() {
        if (isBound) {
            try {
                val msg = Message.obtain(null, PythonBackendService.MSG_UNREGISTER_CLIENT).apply {
                    replyTo = clientMessenger
                }
                serviceMessenger?.send(msg)
            } catch (_: RemoteException) {}
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
            serviceMessenger = null
        }
    }

    fun startBackend(projectDir: File, backendFileName: String, pipDir: File) {
        currentSessionId = UUID.randomUUID().toString()
        bindService()

        val data = Bundle().apply {
            putString(PythonBackendService.KEY_PROJECT_DIR, projectDir.absolutePath)
            putString(PythonBackendService.KEY_BACKEND_FILE, backendFileName)
            putString(PythonBackendService.KEY_PIP_DIR, pipDir.absolutePath)
            putString(PythonBackendService.KEY_SESSION_ID, currentSessionId)
        }

        val msg = Message.obtain(null, PythonBackendService.MSG_START_BACKEND).apply {
            this.data = data
            replyTo = clientMessenger
        }

        try {
            serviceMessenger?.send(msg)
        } catch (_: RemoteException) {
            // Queue for when service binds
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    serviceMessenger?.send(msg)
                } catch (_: RemoteException) {}
            }, 500)
        }
    }

    fun stopBackend() {
        val data = Bundle().apply {
            putString(PythonBackendService.KEY_SESSION_ID, currentSessionId)
        }
        val msg = Message.obtain(null, PythonBackendService.MSG_STOP_BACKEND).apply {
            this.data = data
        }
        try {
            serviceMessenger?.send(msg)
        } catch (_: RemoteException) {}
        _status.value = BackendProcessStatus.STOPPED
    }

    fun restartBackend(projectDir: File, backendFileName: String, pipDir: File) {
        stopBackend()
        startBackend(projectDir, backendFileName, pipDir)
    }

    fun invokeBridge(action: String, payload: String, callback: (result: String?, error: String?) -> Unit) {
        val requestId = UUID.randomUUID().toString()
        pendingRequests[requestId] = callback

        val data = Bundle().apply {
            putString(PythonBackendService.KEY_ACTION, action)
            putString(PythonBackendService.KEY_PAYLOAD, payload)
            putString(PythonBackendService.KEY_REQUEST_ID, requestId)
            putString(PythonBackendService.KEY_SESSION_ID, currentSessionId)
        }
        val msg = Message.obtain(null, PythonBackendService.MSG_INVOKE_BRIDGE).apply {
            this.data = data
            replyTo = clientMessenger
        }

        try {
            serviceMessenger?.send(msg)
        } catch (e: RemoteException) {
            pendingRequests.remove(requestId)
            callback(null, "IPC Error: ${e.message}")
        }
    }

    fun addLog(text: String, isError: Boolean = false) {
        val line = TerminalLine(text = text, isError = isError)
        _logs.value = _logs.value + line
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private inner class ClientIncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                PythonBackendService.MSG_BACKEND_LOG -> {
                    val text = msg.data.getString(PythonBackendService.KEY_TEXT) ?: ""
                    val isError = msg.data.getBoolean(PythonBackendService.KEY_IS_ERROR, false)
                    addLog(text, isError)
                }
                PythonBackendService.MSG_BACKEND_STATUS -> {
                    val statusStr = msg.data.getString(PythonBackendService.KEY_STATUS) ?: ""
                    val newStatus = try {
                        BackendProcessStatus.valueOf(statusStr)
                    } catch (_: Exception) {
                        BackendProcessStatus.STOPPED
                    }
                    _status.value = newStatus
                }
                PythonBackendService.MSG_BRIDGE_RESPONSE -> {
                    val requestId = msg.data.getString(PythonBackendService.KEY_REQUEST_ID) ?: ""
                    val result = msg.data.getString(PythonBackendService.KEY_RESULT)
                    val error = msg.data.getString(PythonBackendService.KEY_ERROR)
                    val callback = pendingRequests.remove(requestId)
                    callback?.invoke(result, error)
                }
                else -> super.handleMessage(msg)
            }
        }
    }
}
