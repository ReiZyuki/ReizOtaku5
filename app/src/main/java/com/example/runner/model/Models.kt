package com.example.runner.model

import java.io.File

enum class ProjectType {
    HTML,
    HTML_PYTHON_BACKEND,
    PYTHON,
    EMPTY,
    UNSUPPORTED
}

data class RunnerJsonConfig(
    val type: String? = null,
    val entry: String? = null,
    val html: String? = null,
    val backend: String? = null
)

data class ProjectInfo(
    val projectDir: File,
    val type: ProjectType,
    val htmlEntry: File? = null,
    val pythonEntry: File? = null,
    val backendEntry: File? = null,
    val requirementsFile: File? = null,
    val runnerJsonFile: File? = null
)

sealed class RunState {
    data class Loading(val message: String) : RunState()
    object EmptyProject : RunState()
    object UnsupportedProject : RunState()
    data class HtmlRunning(
        val projectInfo: ProjectInfo,
        val hasBackend: Boolean,
        val backendRunning: Boolean,
        val backendLogs: List<String> = emptyList()
    ) : RunState()
    data class PythonRunning(
        val projectInfo: ProjectInfo,
        val logs: List<String> = emptyList(),
        val isRunning: Boolean = false,
        val exitCode: Int? = null
    ) : RunState()
    data class FailedToRun(
        val message: String,
        val errorDetails: String
    ) : RunState()
}

enum class BackendProcessStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class TerminalLine(
    val text: String,
    val isError: Boolean = false,
    val isCommand: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
