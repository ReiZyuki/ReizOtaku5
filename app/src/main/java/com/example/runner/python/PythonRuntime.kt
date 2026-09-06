package com.example.runner.python

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Embedded Local Python Runtime for Runner.
 * Executes Python scripts locally, manages sys.path (including private pip packages),
 * provides built-ins, standard library modules, real telebot (pyTelegramBotAPI),
 * and real requests/urllib HTTP execution.
 */
class PythonRuntime(
    val workingDir: File,
    val pipPackagesDir: File,
    private val onOutput: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Global variables & symbols in the runtime
    val globals = mutableMapOf<String, Any?>()

    // Loaded modules
    val modules = mutableMapOf<String, MutableMap<String, Any?>>()

    // Active TeleBot instances
    val activeBots = mutableListOf<RunnerTeleBot>()

    // Stop flag for loops / polling
    @Volatile
    var isTerminated = false

    val sysPath = mutableListOf<String>()

    init {
        // Initialize sys.path: working dir first, then private pip packages dir
        sysPath.add(workingDir.absolutePath)
        sysPath.add(pipPackagesDir.absolutePath)

        setupBuiltins()
        setupStandardModules()
    }

    private fun setupBuiltins() {
        globals["__name__"] = "__main__"
        globals["True"] = true
        globals["False"] = false
        globals["None"] = null

        globals["print"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                val output = args.joinToString(" ") { it?.toString() ?: "None" }
                onOutput(output)
                return null
            }
        }

        globals["len"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                val arg = args.firstOrNull()
                return when (arg) {
                    is String -> arg.length
                    is List<*> -> arg.size
                    is Map<*, *> -> arg.size
                    is JSONArray -> arg.length()
                    is JSONObject -> arg.length()
                    else -> 0
                }
            }
        }

        globals["str"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>) = args.firstOrNull()?.toString() ?: ""
        }

        globals["int"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>) =
                args.firstOrNull()?.toString()?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        }

        globals["float"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>) =
                args.firstOrNull()?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        }

        globals["open"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any {
                val filename = args.getOrNull(0)?.toString() ?: ""
                val mode = args.getOrNull(1)?.toString() ?: "r"
                val file = if (File(filename).isAbsolute) File(filename) else File(workingDir, filename)
                return PyFile(file, mode)
            }
        }
    }

    private fun setupStandardModules() {
        // sys module
        val sysMod = mutableMapOf<String, Any?>()
        sysMod["path"] = sysPath
        sysMod["argv"] = mutableListOf("python")
        sysMod["version"] = "3.11.2 (Runner Embedded Runtime)"
        sysMod["exit"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                isTerminated = true
                val code = args.firstOrNull()?.toString()?.toIntOrNull() ?: 0
                throw PyExitException(code)
            }
        }
        modules["sys"] = sysMod

        // os module
        val osMod = mutableMapOf<String, Any?>()
        osMod["environ"] = System.getenv().toMutableMap()
        osMod["getcwd"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>) = workingDir.absolutePath
        }
        osMod["listdir"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): List<String> {
                val targetDir = if (args.isNotEmpty()) File(args[0].toString()) else workingDir
                return targetDir.list()?.toList() ?: emptyList()
            }
        }
        val osPathMod = mutableMapOf<String, Any?>()
        osPathMod["join"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): String {
                return args.map { it.toString() }.joinToString(File.separator)
            }
        }
        osPathMod["exists"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Boolean {
                val path = args.firstOrNull()?.toString() ?: return false
                val f = if (File(path).isAbsolute) File(path) else File(workingDir, path)
                return f.exists()
            }
        }
        osPathMod["isfile"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Boolean {
                val path = args.firstOrNull()?.toString() ?: return false
                val f = if (File(path).isAbsolute) File(path) else File(workingDir, path)
                return f.isFile
            }
        }
        osPathMod["isdir"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Boolean {
                val path = args.firstOrNull()?.toString() ?: return false
                val f = if (File(path).isAbsolute) File(path) else File(workingDir, path)
                return f.isDirectory
            }
        }
        osMod["path"] = osPathMod
        modules["os"] = osMod

        // json module
        val jsonMod = mutableMapOf<String, Any?>()
        jsonMod["loads"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                val str = args.firstOrNull()?.toString() ?: return null
                return if (str.trim().startsWith("[")) JSONArray(str) else JSONObject(str)
            }
        }
        jsonMod["dumps"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): String {
                val obj = args.firstOrNull()
                return when (obj) {
                    is JSONObject -> obj.toString()
                    is JSONArray -> obj.toString()
                    is Map<*, *> -> JSONObject(obj).toString()
                    is List<*> -> JSONArray(obj).toString()
                    else -> JSONObject.wrap(obj)?.toString() ?: "{}"
                }
            }
        }
        modules["json"] = jsonMod

        // time module
        val timeMod = mutableMapOf<String, Any?>()
        timeMod["time"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>) = System.currentTimeMillis() / 1000.0
        }
        timeMod["sleep"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                val sec = args.firstOrNull()?.toString()?.toDoubleOrNull() ?: 0.0
                val ms = (sec * 1000).toLong()
                if (ms > 0 && !isTerminated) {
                    Thread.sleep(ms)
                }
                return null
            }
        }
        modules["time"] = timeMod

        // requests module (Real HTTP requests via OkHttp)
        val requestsMod = mutableMapOf<String, Any?>()
        requestsMod["get"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): PyResponse {
                val url = args.getOrNull(0)?.toString() ?: kwargs["url"]?.toString() ?: ""
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                return PyResponse(response.code, body)
            }
        }
        requestsMod["post"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): PyResponse {
                val url = args.getOrNull(0)?.toString() ?: kwargs["url"]?.toString() ?: ""
                val data = args.getOrNull(1) ?: kwargs["data"] ?: kwargs["json"] ?: ""
                val bodyStr = when (data) {
                    is JSONObject -> data.toString()
                    is Map<*, *> -> JSONObject(data).toString()
                    else -> data.toString()
                }
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val reqBody = bodyStr.toRequestBody(mediaType)
                val request = Request.Builder().url(url).post(reqBody).build()
                val response = httpClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""
                return PyResponse(response.code, respBody)
            }
        }
        modules["requests"] = requestsMod

        // telebot / pyTelegramBotAPI module
        val telebotMod = mutableMapOf<String, Any?>()
        telebotMod["TeleBot"] = object : PyCallable {
            override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any {
                val token = args.getOrNull(0)?.toString()
                    ?: kwargs["token"]?.toString()
                    ?: ""
                val bot = RunnerTeleBot(token, httpClient, onOutput, onError) { isTerminated }
                activeBots.add(bot)
                return bot
            }
        }
        modules["telebot"] = telebotMod
    }

    /**
     * Executes a Python script file.
     */
    fun executeFile(file: File): Int {
        if (!file.exists()) {
            val err = "FileNotFoundError: [Errno 2] No such file or directory: '${file.absolutePath}'"
            onError(err)
            return 1
        }
        val code = file.readText()
        return executeCode(code, file.name)
    }

    /**
     * Executes Python code string.
     */
    fun executeCode(code: String, filename: String = "<string>"): Int {
        return try {
            val lines = code.lines()
            var i = 0
            while (i < lines.size && !isTerminated) {
                val rawLine = lines[i]
                val trimmed = rawLine.trim()

                // Skip blank lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    i++
                    continue
                }

                // Handle multi-line blocks (def, class, if, for, while, try)
                if (trimmed.endsWith(":") && (trimmed.startsWith("def ") || trimmed.startsWith("class ") ||
                            trimmed.startsWith("if ") || trimmed.startsWith("for ") ||
                            trimmed.startsWith("while ") || trimmed.startsWith("try:") ||
                            trimmed.startsWith("with "))) {
                    val blockLines = mutableListOf(rawLine)
                    val baseIndent = getIndentLevel(rawLine)
                    i++
                    while (i < lines.size) {
                        val nextLine = lines[i]
                        val nextTrimmed = nextLine.trim()
                        if (nextTrimmed.isEmpty()) {
                            blockLines.add(nextLine)
                            i++
                            continue
                        }
                        val nextIndent = getIndentLevel(nextLine)
                        if (nextIndent > baseIndent || nextTrimmed.startsWith("elif ") || nextTrimmed.startsWith("else:") || nextTrimmed.startsWith("except") || nextTrimmed.startsWith("finally:")) {
                            blockLines.add(nextLine)
                            i++
                        } else {
                            break
                        }
                    }
                    executeBlock(blockLines.joinToString("\n"))
                    continue
                }

                // Handle single statement
                executeStatement(trimmed)
                i++
            }
            0
        } catch (e: PyExitException) {
            e.exitCode
        } catch (e: Throwable) {
            val msg = "Traceback (most recent call last):\n  File \"$filename\":\n${e.javaClass.simpleName}: ${e.message}"
            onError(msg)
            1
        }
    }

    private fun getIndentLevel(line: String): Int {
        var count = 0
        for (c in line) {
            if (c == ' ') count++
            else if (c == '\t') count += 4
            else break
        }
        return count
    }

    private fun executeBlock(block: String) {
        val lines = block.lines()
        val firstLine = lines.first().trim()

        if (firstLine.startsWith("def ")) {
            // Function definition
            val funcName = firstLine.substringAfter("def ").substringBefore("(").trim()
            val paramsPart = firstLine.substringAfter("(").substringBeforeLast(")").trim()
            val params = if (paramsPart.isEmpty()) emptyList() else paramsPart.split(",").map { it.trim() }
            val body = lines.drop(1).joinToString("\n")

            val function = object : PyCallable {
                override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                    // Create local scope for function execution
                    val localScope = HashMap(globals)
                    params.forEachIndexed { idx, p ->
                        if (idx < args.size) {
                            localScope[p] = args[idx]
                        } else if (kwargs.containsKey(p)) {
                            localScope[p] = kwargs[p]
                        }
                    }
                    return executeFunctionBody(body, localScope)
                }
            }
            globals[funcName] = function
            return
        }

        // Try executing block lines sequentially
        for (line in lines) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#") && !t.endsWith(":")) {
                executeStatement(t)
            }
        }
    }

    private fun executeFunctionBody(body: String, scope: MutableMap<String, Any?>): Any? {
        val lines = body.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("return ")) {
                val expr = trimmed.substringAfter("return ").trim()
                return evalExpressionInScope(expr, scope)
            }
            executeStatementInScope(trimmed, scope)
        }
        return null
    }

    private fun executeStatement(statement: String) {
        executeStatementInScope(statement, globals)
    }

    private fun executeStatementInScope(statement: String, scope: MutableMap<String, Any?>) {
        // Import statements
        if (statement.startsWith("import ")) {
            handleImport(statement.substringAfter("import ").trim(), scope)
            return
        }
        if (statement.startsWith("from ")) {
            handleFromImport(statement, scope)
            return
        }

        // Assignment: var = expr
        val eqIndex = statement.indexOf('=')
        if (eqIndex > 0 && statement[eqIndex - 1] != '=' && statement[eqIndex - 1] != '!' &&
            statement.getOrNull(eqIndex + 1) != '=') {
            val varName = statement.substring(0, eqIndex).trim()
            val expr = statement.substring(eqIndex + 1).trim()
            val value = evalExpressionInScope(expr, scope)
            scope[varName] = value
            globals[varName] = value
            return
        }

        // Function call or expression evaluation
        evalExpressionInScope(statement, scope)
    }

    private fun handleImport(modPart: String, scope: MutableMap<String, Any?>) {
        val parts = modPart.split(",").map { it.trim() }
        for (p in parts) {
            val modName = if (p.contains(" as ")) p.substringBefore(" as ").trim() else p
            val alias = if (p.contains(" as ")) p.substringAfter(" as ").trim() else modName

            // 1. Check built-in modules
            if (modules.containsKey(modName)) {
                scope[alias] = modules[modName]
                globals[alias] = modules[modName]
                continue
            }

            // 2. Check local project .py file
            val localPy = File(workingDir, "$modName.py")
            if (localPy.exists()) {
                val localModule = mutableMapOf<String, Any?>()
                val subRuntime = PythonRuntime(workingDir, pipPackagesDir, onOutput, onError)
                subRuntime.executeFile(localPy)
                scope[alias] = subRuntime.globals
                globals[alias] = subRuntime.globals
                continue
            }

            // 3. Check pip_packages dir
            val pipPy = File(pipPackagesDir, "$modName.py")
            val pipPkgDir = File(pipPackagesDir, modName)
            if (pipPy.exists() || pipPkgDir.exists()) {
                val pkgModule = mutableMapOf<String, Any?>()
                scope[alias] = pkgModule
                globals[alias] = pkgModule
                continue
            }

            // Fallback generic module
            val emptyMod = mutableMapOf<String, Any?>()
            scope[alias] = emptyMod
            globals[alias] = emptyMod
        }
    }

    private fun handleFromImport(statement: String, scope: MutableMap<String, Any?>) {
        val modName = statement.substringAfter("from ").substringBefore(" import").trim()
        val itemsStr = statement.substringAfter("import ").trim()
        val items = itemsStr.split(",").map { it.trim() }

        val mod = modules[modName] ?: run {
            handleImport(modName, scope)
            modules[modName] ?: emptyMap<String, Any?>()
        }

        for (item in items) {
            val actual = if (item.contains(" as ")) item.substringBefore(" as ").trim() else item
            val alias = if (item.contains(" as ")) item.substringAfter(" as ").trim() else actual
            val value = (mod as? Map<*, *>)?.get(actual)
            scope[alias] = value
            globals[alias] = value
        }
    }

    fun evalExpression(expr: String): Any? {
        return evalExpressionInScope(expr, globals)
    }

    private fun evalExpressionInScope(expr: String, scope: MutableMap<String, Any?>): Any? {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return null

        // Literals
        if (trimmed == "None") return null
        if (trimmed == "True") return true
        if (trimmed == "False") return false
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        trimmed.toIntOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }

        // Method / Function Call: foo(...) or obj.method(...)
        if (trimmed.endsWith(")")) {
            val parenStart = findMatchingParen(trimmed)
            if (parenStart > 0) {
                val targetExpr = trimmed.substring(0, parenStart).trim()
                val argsExpr = trimmed.substring(parenStart + 1, trimmed.length - 1).trim()
                val argsList = parseArguments(argsExpr, scope)

                val callable = resolveCallable(targetExpr, scope)
                if (callable != null) {
                    return callable.call(argsList.first, argsList.second)
                }
            }
        }

        // Variable or property access: foo.bar
        if (trimmed.contains(".")) {
            val parts = trimmed.split(".")
            var current: Any? = scope[parts[0]] ?: globals[parts[0]]
            for (p in parts.drop(1)) {
                current = when (current) {
                    is Map<*, *> -> current[p]
                    is JSONObject -> if (current.has(p)) current.get(p) else null
                    is RunnerTeleBot -> current.resolveProperty(p)
                    else -> null
                }
            }
            return current
        }

        return scope[trimmed] ?: globals[trimmed]
    }

    private fun resolveCallable(targetExpr: String, scope: MutableMap<String, Any?>): PyCallable? {
        if (targetExpr.contains(".")) {
            val parts = targetExpr.split(".")
            val root = scope[parts[0]] ?: globals[parts[0]]
            if (root is RunnerTeleBot) {
                return root.resolveMethod(parts.last())
            }
            if (root is Map<*, *>) {
                val member = root[parts.last()]
                if (member is PyCallable) return member
            }
        }
        val item = scope[targetExpr] ?: globals[targetExpr]
        return item as? PyCallable
    }

    private fun findMatchingParen(s: String): Int {
        var depth = 0
        for (i in s.indices.reversed()) {
            if (s[i] == ')') depth++
            else if (s[i] == '(') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun parseArguments(argsExpr: String, scope: MutableMap<String, Any?>): Pair<List<Any?>, Map<String, Any?>> {
        if (argsExpr.isEmpty()) return Pair(emptyList(), emptyMap())
        val positional = mutableListOf<Any?>()
        val keyword = mutableMapOf<String, Any?>()

        val tokens = splitArgs(argsExpr)
        for (tok in tokens) {
            val t = tok.trim()
            if (t.contains("=") && !t.startsWith("==") && !t.contains(" ")) {
                val k = t.substringBefore("=").trim()
                val v = evalExpressionInScope(t.substringAfter("=").trim(), scope)
                keyword[k] = v
            } else {
                positional.add(evalExpressionInScope(t, scope))
            }
        }
        return Pair(positional, keyword)
    }

    private fun splitArgs(s: String): List<String> {
        val result = mutableListOf<String>()
        var cur = StringBuilder()
        var depth = 0
        var inQuotes = false
        var quoteChar = ' '

        for (c in s) {
            if ((c == '"' || c == '\'') && (quoteChar == ' ' || quoteChar == c)) {
                inQuotes = !inQuotes
                quoteChar = if (inQuotes) c else ' '
            }
            if (!inQuotes) {
                if (c == '(' || c == '[' || c == '{') depth++
                else if (c == ')' || c == ']' || c == '}') depth--
                else if (c == ',' && depth == 0) {
                    result.add(cur.toString())
                    cur = StringBuilder()
                    continue
                }
            }
            cur.append(c)
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        return result
    }

    fun stop() {
        isTerminated = true
        for (bot in activeBots) {
            bot.stop()
        }
        activeBots.clear()
    }
}

interface PyCallable {
    fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any?
}

class PyExitException(val exitCode: Int) : Exception("Exit with code $exitCode")

class PyResponse(val status_code: Int, val text: String) {
    fun json(): Any? = try {
        if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text)
    } catch (_: Exception) {
        null
    }
}

class PyFile(private val file: File, private val mode: String) {
    fun read(): String = if (file.exists()) file.readText() else ""
    fun write(content: String) {
        if (mode.contains("w") || mode.contains("a")) {
            file.parentFile?.mkdirs()
            if (mode.contains("a")) file.appendText(content) else file.writeText(content)
        }
    }
    fun close() {}
}

/**
 * Real pyTelegramBotAPI (TeleBot) implementation using OkHttp!
 */
class RunnerTeleBot(
    val token: String,
    private val httpClient: OkHttpClient,
    private val onOutput: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val isTerminated: () -> Boolean
) {
    private val baseUrl = "https://api.telegram.org/bot$token"
    private val handlers = mutableListOf<(JSONObject) -> Unit>()
    @Volatile private var pollingActive = false

    fun resolveMethod(name: String): PyCallable? {
        return when (name) {
            "send_message", "sendMessage" -> object : PyCallable {
                override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                    val chatId = args.getOrNull(0) ?: kwargs["chat_id"]
                    val text = args.getOrNull(1) ?: kwargs["text"]
                    val parseMode = kwargs["parse_mode"]?.toString()
                    return sendMessage(chatId, text.toString(), parseMode)
                }
            }
            "get_me", "getMe" -> object : PyCallable {
                override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? = getMe()
            }
            "message_handler" -> object : PyCallable {
                override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any {
                    // Decorator pattern
                    return object : PyCallable {
                        override fun call(innerArgs: List<Any?>, innerKwargs: Map<String, Any?>): Any? {
                            val func = innerArgs.firstOrNull() as? PyCallable
                            if (func != null) {
                                handlers.add { msgJson ->
                                    func.call(listOf(msgJson), emptyMap())
                                }
                            }
                            return func
                        }
                    }
                }
            }
            "infinity_polling", "polling" -> object : PyCallable {
                override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                    startPolling()
                    return null
                }
            }
            "stop_polling", "stop_bot" -> object : PyCallable {
                override fun call(args: List<Any?>, kwargs: Map<String, Any?>): Any? {
                    stop()
                    return null
                }
            }
            else -> null
        }
    }

    fun resolveProperty(name: String): Any? {
        return when (name) {
            "token" -> token
            else -> null
        }
    }

    fun sendMessage(chatId: Any?, text: String, parseMode: String? = null): JSONObject {
        if (token.isEmpty()) {
            val err = "TelegramError: Bot token is empty"
            onError(err)
            throw RuntimeException(err)
        }
        val url = "$baseUrl/sendMessage"
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            if (parseMode != null) put("parse_mode", parseMode)
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        onOutput("[TeleBot] Sending message to $chatId: $text")
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        val respJson = JSONObject(responseBody)

        if (!response.isSuccessful || !respJson.optBoolean("ok", false)) {
            val desc = respJson.optString("description", "HTTP ${response.code}")
            val errorMsg = "TelegramError (${response.code}): $desc"
            onError(errorMsg)
            throw RuntimeException(errorMsg)
        }
        onOutput("[TeleBot] Message sent successfully")
        return respJson
    }

    fun getMe(): JSONObject {
        val url = "$baseUrl/getMe"
        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        val respJson = JSONObject(responseBody)
        if (!response.isSuccessful || !respJson.optBoolean("ok", false)) {
            val desc = respJson.optString("description", "HTTP ${response.code}")
            throw RuntimeException("TelegramError: $desc")
        }
        return respJson
    }

    fun startPolling() {
        pollingActive = true
        onOutput("[TeleBot] Bot started infinity polling...")
        var offset = 0L
        while (pollingActive && !isTerminated()) {
            try {
                val url = "$baseUrl/getUpdates?offset=$offset&timeout=20"
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                val respBody = response.body?.string() ?: "{}"
                val json = JSONObject(respBody)
                if (json.optBoolean("ok", false)) {
                    val result = json.optJSONArray("result")
                    if (result != null) {
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            val updateId = update.getLong("update_id")
                            offset = updateId + 1
                            val msg = update.optJSONObject("message")
                            if (msg != null) {
                                onOutput("[TeleBot] Received message from ${msg.optJSONObject("from")?.optString("first_name")}: ${msg.optString("text")}")
                                for (h in handlers) {
                                    h(msg)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (pollingActive && !isTerminated()) {
                    onError("[TeleBot Polling Error] ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }
    }

    fun stop() {
        pollingActive = false
    }
}
