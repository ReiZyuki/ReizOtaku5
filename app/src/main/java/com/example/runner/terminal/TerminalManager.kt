package com.example.runner.terminal

import com.example.runner.backend.BackendClientManager
import com.example.runner.model.TerminalLine
import com.example.runner.pip.PipPackageManager
import com.example.runner.pip.YtDlpTool
import com.example.runner.project.ProjectStorageManager
import com.example.runner.python.PythonRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TerminalManager(
    private val storageManager: ProjectStorageManager,
    private val pipManager: PipPackageManager,
    private val backendClient: BackendClientManager,
    private val coroutineScope: CoroutineScope
) {

    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(
        listOf(
            TerminalLine("Runner Terminal [Embedded Local Environment]", isCommand = false),
            TerminalLine("Type 'help' for available commands or execute python/pip/yt-dlp scripts.\n", isCommand = false)
        )
    )
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    private val commandHistory = mutableListOf<String>()
    var historyIndex = -1
        private set

    val isExecuting = MutableStateFlow(false)

    fun executeCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        commandHistory.add(command)
        historyIndex = commandHistory.size

        appendLine(TerminalLine("$ $command", isCommand = true))

        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }

        val parts = parseCommandLine(command)
        if (parts.isEmpty()) return

        val cmd = parts[0].lowercase()
        val args = parts.drop(1)

        coroutineScope.launch(Dispatchers.IO) {
            isExecuting.value = true
            try {
                when (cmd) {
                    "help" -> showHelp()
                    "pwd" -> appendLine(TerminalLine(storageManager.activeProjectDir.absolutePath))
                    "ls" -> listFiles(args)
                    "cat" -> catFile(args)
                    "status" -> showStatus()
                    "pip" -> handlePip(args)
                    "python", "python3" -> handlePython(args)
                    "yt-dlp", "ytdlp" -> handleYtDlp(args)
                    "stop" -> {
                        backendClient.stopBackend()
                        appendLine(TerminalLine("Backend process stopped."))
                    }
                    "restart" -> {
                        appendLine(TerminalLine("Restarting active project backend..."))
                        backendClient.restartBackend(
                            storageManager.activeProjectDir,
                            "app2.py",
                            storageManager.pipPackagesDir
                        )
                    }
                    else -> {
                        appendLine(TerminalLine("runner: command not found: $cmd. Type 'help' for available commands.", isError = true))
                    }
                }
            } catch (e: Exception) {
                appendLine(TerminalLine("Error: ${e.message}", isError = true))
            } finally {
                isExecuting.value = false
            }
        }
    }

    fun appendLine(line: TerminalLine) {
        _terminalLines.value = _terminalLines.value + line
    }

    fun getPreviousCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex > 0) {
            historyIndex--
            return commandHistory[historyIndex]
        }
        return commandHistory.firstOrNull()
    }

    fun getNextCommand(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex < commandHistory.size - 1) {
            historyIndex++
            return commandHistory[historyIndex]
        }
        historyIndex = commandHistory.size
        return ""
    }

    private fun showHelp() {
        val help = """
            Available Runner Commands:
              python <file.py>       Execute local Python script
              python -c "<code>"     Execute inline Python code
              pip install <pkg>      Install Python package from PyPI into private dir
              pip list               List installed pip packages
              yt-dlp <url>           Download media to Download/ folder
              ls [dir]               List directory contents
              pwd                    Print current working project directory
              cat <file>             Display contents of a file
              status                 Display active project & backend process status
              stop                   Stop the active Python backend process
              restart                Restart the active Python backend process
              clear                  Clear terminal output
              help                   Show this help menu
        """.trimIndent()
        appendLine(TerminalLine(help))
    }

    private fun listFiles(args: List<String>) {
        val target = if (args.isNotEmpty()) File(storageManager.activeProjectDir, args[0]) else storageManager.activeProjectDir
        if (!target.exists()) {
            appendLine(TerminalLine("ls: ${target.name}: No such file or directory", isError = true))
            return
        }
        val files = target.listFiles() ?: emptyArray()
        val text = files.joinToString("  ") { f ->
            if (f.isDirectory) "${f.name}/" else f.name
        }
        appendLine(TerminalLine(if (text.isEmpty()) "(empty directory)" else text))
    }

    private fun catFile(args: List<String>) {
        if (args.isEmpty()) {
            appendLine(TerminalLine("cat: missing file argument", isError = true))
            return
        }
        val file = File(storageManager.activeProjectDir, args[0])
        if (!file.exists()) {
            appendLine(TerminalLine("cat: ${args[0]}: No such file or directory", isError = true))
            return
        }
        appendLine(TerminalLine(file.readText()))
    }

    private fun showStatus() {
        val activeFiles = storageManager.activeProjectDir.listFiles()?.map { it.name } ?: emptyList()
        val statusText = """
            Runner Status:
              Project Dir: ${storageManager.activeProjectDir.absolutePath}
              Files: ${activeFiles.joinToString(", ").ifEmpty { "(none)" }}
              Backend Status: ${backendClient.status.value.name}
              Pip Storage: ${storageManager.pipPackagesDir.absolutePath}
              Download Storage: ${storageManager.normalDownloadDir.absolutePath}
        """.trimIndent()
        appendLine(TerminalLine(statusText))
    }

    private fun handlePip(args: List<String>) {
        if (args.isEmpty()) {
            appendLine(TerminalLine("Usage: pip <install|list> [package]", isError = true))
            return
        }
        when (args[0].lowercase()) {
            "list" -> {
                val pkgs = pipManager.listInstalledPackages()
                if (pkgs.isEmpty()) {
                    appendLine(TerminalLine("Package           Version\n----------------- -------\n(no packages installed)"))
                } else {
                    val header = "Package           Location\n----------------- -----------------"
                    val lines = pkgs.joinToString("\n") { it.padEnd(18) + storageManager.pipPackagesDir.name }
                    appendLine(TerminalLine("$header\n$lines"))
                }
            }
            "install" -> {
                if (args.size < 2) {
                    appendLine(TerminalLine("ERROR: You must give at least one requirement to install", isError = true))
                    return
                }
                for (pkg in args.drop(1)) {
                    val ok = pipManager.installPackage(pkg)
                    if (!ok) {
                        appendLine(TerminalLine("Failed to install $pkg", isError = true))
                    }
                }
            }
            else -> {
                appendLine(TerminalLine("pip: unknown command '${args[0]}'", isError = true))
            }
        }
    }

    private fun handlePython(args: List<String>) {
        if (args.isEmpty()) {
            appendLine(TerminalLine("Python 3.11.2 (Runner embedded runtime)\nType python <file.py> or python -c \"<code>\""))
            return
        }

        val runtime = PythonRuntime(
            workingDir = storageManager.activeProjectDir,
            pipPackagesDir = storageManager.pipPackagesDir,
            onOutput = { text -> appendLine(TerminalLine(text, isError = false)) },
            onError = { text -> appendLine(TerminalLine(text, isError = true)) }
        )

        if (args[0] == "-c" && args.size > 1) {
            val inlineCode = args.drop(1).joinToString(" ")
            val exitCode = runtime.executeCode(inlineCode)
            if (exitCode != 0) {
                appendLine(TerminalLine("Process finished with exit code $exitCode", isError = true))
            }
            return
        }

        val targetFile = File(storageManager.activeProjectDir, args[0])
        if (!targetFile.exists()) {
            appendLine(TerminalLine("python: can't open file '${args[0]}': [Errno 2] No such file or directory", isError = true))
            return
        }

        appendLine(TerminalLine("[Running ${targetFile.name}...]"))
        val exitCode = runtime.executeFile(targetFile)
        appendLine(TerminalLine("Process finished with exit code $exitCode"))
    }

    private fun handleYtDlp(args: List<String>) {
        val tool = YtDlpTool(
            downloadDir = storageManager.normalDownloadDir,
            onLog = { text -> appendLine(TerminalLine(text, isError = false)) },
            onError = { text -> appendLine(TerminalLine(text, isError = true)) }
        )
        val exitCode = tool.execute(args)
        if (exitCode != 0) {
            appendLine(TerminalLine("[yt-dlp exited with error code $exitCode]", isError = true))
        }
    }

    private fun parseCommandLine(input: String): List<String> {
        val result = mutableListOf<String>()
        var cur = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '

        for (c in input) {
            if ((c == '"' || c == '\'') && (quoteChar == ' ' || quoteChar == c)) {
                inQuotes = !inQuotes
                quoteChar = if (inQuotes) c else ' '
            } else if (c == ' ' && !inQuotes) {
                if (cur.isNotEmpty()) {
                    result.add(cur.toString())
                    cur = StringBuilder()
                }
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) {
            result.add(cur.toString())
        }
        return result
    }
}
