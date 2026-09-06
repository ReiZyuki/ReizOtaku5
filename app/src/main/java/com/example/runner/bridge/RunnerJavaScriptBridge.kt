package com.example.runner.bridge

import android.webkit.JavascriptInterface
import com.example.runner.backend.BackendClientManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunnerJavaScriptBridge(
    private val backendClient: BackendClientManager
) {

    companion object {
        const val JS_INTERFACE_NAME = "_RunnerNativeBridge"

        val INJECTED_JS_POLYFILL = """
            (function() {
                if (window.RunnerBackend) return;
                var nativeBridge = window._RunnerNativeBridge;
                var baseObj = {
                    sendMessage: function(msg) {
                        var str = typeof msg === 'object' ? JSON.stringify(msg) : String(msg);
                        var raw = nativeBridge.sendMessage(str);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    call: function(fn) {
                        var args = Array.prototype.slice.call(arguments, 1);
                        var raw = nativeBridge.call(fn, JSON.stringify(args));
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    invoke: function(action, payload) {
                        var str = typeof payload === 'object' ? JSON.stringify(payload) : String(payload);
                        var raw = nativeBridge.invoke(action, str);
                        try { return JSON.parse(raw); } catch(e) { return raw; }
                    },
                    status: function() {
                        return nativeBridge.status();
                    }
                };

                if (window.Proxy) {
                    window.RunnerBackend = new Proxy(baseObj, {
                        get: function(target, prop) {
                            if (prop in target) return target[prop];
                            return function() {
                                var args = Array.prototype.slice.call(arguments);
                                var raw = nativeBridge.call(prop, JSON.stringify(args));
                                try { return JSON.parse(raw); } catch(e) { return raw; }
                            };
                        }
                    });
                } else {
                    window.RunnerBackend = baseObj;
                }
            })();
        """.trimIndent()
    }

    @JavascriptInterface
    fun sendMessage(message: String): String {
        return invokeSynchronously("sendMessage", message)
    }

    @JavascriptInterface
    fun call(functionName: String, argsJson: String): String {
        return invokeSynchronously(functionName, argsJson)
    }

    @JavascriptInterface
    fun invoke(action: String, payload: String): String {
        return invokeSynchronously(action, payload)
    }

    @JavascriptInterface
    fun status(): String {
        return backendClient.status.value.name
    }

    private fun invokeSynchronously(action: String, payload: String): String {
        val latch = CountDownLatch(1)
        var responseResult: String? = null
        var responseError: String? = null

        backendClient.invokeBridge(action, payload) { result, error ->
            responseResult = result
            responseError = error
            latch.countDown()
        }

        try {
            val completed = latch.await(20, TimeUnit.SECONDS)
            if (!completed) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "Backend call timed out after 20 seconds")
                }.toString()
            }

            if (responseError != null) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", responseError)
                }.toString()
            }

            return responseResult ?: JSONObject().apply {
                put("ok", true)
            }.toString()

        } catch (e: Exception) {
            return JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "Bridge invocation failed")
            }.toString()
        }
    }
}
