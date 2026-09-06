package com.example.runner.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.runner.backend.BackendClientManager
import com.example.runner.model.ProjectInfo
import com.example.runner.model.ProjectType
import com.example.runner.model.RunState
import com.example.runner.pip.PipPackageManager
import com.example.runner.project.ProjectDetector
import com.example.runner.project.ProjectStorageManager
import com.example.runner.python.PythonRuntime
import com.example.runner.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class RunnerViewModel(application: Application) : AndroidViewModel(application) {

    val storageManager = ProjectStorageManager(application)
    val backendClient = BackendClientManager(application)
    val pipManager = PipPackageManager(
        pipPackagesDir = storageManager.pipPackagesDir,
        onLog = { backendClient.addLog(it, isError = false) },
        onError = { backendClient.addLog(it, isError = true) }
    )
    val terminalManager = TerminalManager(
        storageManager = storageManager,
        pipManager = pipManager,
        backendClient = backendClient,
        coroutineScope = viewModelScope
    )

    private val _runState = MutableStateFlow<RunState>(RunState.Loading("Initializing Runner..."))
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var activePythonExecution: PythonRuntime? = null

    init {
        // Automatic startup (Section 5)
        // Open selected/persisted project folder -> Scan -> Detect -> Run -> Display
        startupScanAndRun()
    }

    fun startupScanAndRun() {
        viewModelScope.launch(Dispatchers.IO) {
            _runState.value = RunState.Loading("Scanning project...")
            val projectDir = storageManager.resolveCurrentProjectDir()
            scanAndRunProject(projectDir)
        }
    }

    fun scanAndRunProject(projectDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            _runState.value = RunState.Loading("Detecting project type...")

            // Stop previous backend or runtime if running (Section 14, 30, 31)
            stopProjectInternal()

            val projectInfo = ProjectDetector.detect(projectDir)
            terminalManager.appendLine(
                com.example.runner.model.TerminalLine(
                    "[Runner] Detected project type: ${projectInfo.type.name} in ${projectDir.name}"
                )
            )

            // Process requirements.txt if present (Section 23)
            if (projectInfo.requirementsFile != null && projectInfo.requirementsFile.exists()) {
                _runState.value = RunState.Loading("Checking requirements.txt dependencies...")
                terminalManager.appendLine(
                    com.example.runner.model.TerminalLine("[pip] Checking dependencies in requirements.txt...")
                )
                pipManager.installRequirements(projectInfo.requirementsFile)
            }

            when (projectInfo.type) {
                ProjectType.EMPTY -> {
                    _runState.value = RunState.EmptyProject
                }
                ProjectType.UNSUPPORTED -> {
                    _runState.value = RunState.UnsupportedProject
                }
                ProjectType.HTML -> {
                    _runState.value = RunState.HtmlRunning(
                        projectInfo = projectInfo,
                        hasBackend = false,
                        backendRunning = false
                    )
                }
                ProjectType.HTML_PYTHON_BACKEND -> {
                    val backendFile = projectInfo.backendEntry?.name ?: "app2.py"
                    _runState.value = RunState.HtmlRunning(
                        projectInfo = projectInfo,
                        hasBackend = true,
                        backendRunning = false
                    )

                    // Start separate Runner-managed Python backend process (Section 12 & 14)
                    backendClient.startBackend(
                        projectDir = projectDir,
                        backendFileName = backendFile,
                        pipDir = storageManager.pipPackagesDir
                    )

                    _runState.value = RunState.HtmlRunning(
                        projectInfo = projectInfo,
                        hasBackend = true,
                        backendRunning = true
                    )
                }
                ProjectType.PYTHON -> {
                    val entry = projectInfo.pythonEntry
                    if (entry == null || !entry.exists()) {
                        _runState.value = RunState.FailedToRun(
                            message = "Project Failed to Run",
                            errorDetails = "Python entry point not found."
                        )
                        return@launch
                    }

                    val logs = mutableListOf<String>()
                    _runState.value = RunState.PythonRunning(
                        projectInfo = projectInfo,
                        logs = logs,
                        isRunning = true
                    )

                    val runtime = PythonRuntime(
                        workingDir = projectDir,
                        pipPackagesDir = storageManager.pipPackagesDir,
                        onOutput = { line ->
                            logs.add(line)
                            terminalManager.appendLine(com.example.runner.model.TerminalLine(line))
                            _runState.value = RunState.PythonRunning(
                                projectInfo = projectInfo,
                                logs = logs.toList(),
                                isRunning = true
                            )
                        },
                        onError = { errLine ->
                            logs.add(errLine)
                            terminalManager.appendLine(com.example.runner.model.TerminalLine(errLine, isError = true))
                            _runState.value = RunState.PythonRunning(
                                projectInfo = projectInfo,
                                logs = logs.toList(),
                                isRunning = true
                            )
                        }
                    )
                    activePythonExecution = runtime

                    val exitCode = runtime.executeFile(entry)
                    _runState.value = RunState.PythonRunning(
                        projectInfo = projectInfo,
                        logs = logs.toList(),
                        isRunning = false,
                        exitCode = exitCode
                    )
                }
            }
        }
    }

    fun onFolderSelected(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _runState.value = RunState.Loading("Loading selected folder...")
            try {
                storageManager.setPersistedFolderUri(treeUri.toString())
                val docFile = DocumentFile.fromTreeUri(getApplication(), treeUri)
                if (docFile != null && docFile.exists()) {
                    storageManager.syncFromDocumentTree(docFile, storageManager.activeProjectDir)
                    scanAndRunProject(storageManager.activeProjectDir)
                } else {
                    _runState.value = RunState.FailedToRun("Project Failed to Run", "Unable to open folder.")
                }
            } catch (e: Exception) {
                _runState.value = RunState.FailedToRun("Project Failed to Run", e.message ?: "Folder selection failed")
            }
        }
    }

    fun importZip(inputStream: InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            _runState.value = RunState.Loading("Importing and extracting project archive...")
            try {
                val dir = storageManager.importZipArchive(inputStream)
                scanAndRunProject(dir)
            } catch (e: Exception) {
                _runState.value = RunState.FailedToRun("Project Failed to Run", e.message ?: "Failed to import ZIP")
            }
        }
    }

    fun downloadProject(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _runState.value = RunState.Loading("Downloading project from $url...")
            try {
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    _runState.value = RunState.FailedToRun(
                        "Project Failed to Run",
                        "Download failed: HTTP ${response.code} ${response.message}"
                    )
                    return@launch
                }

                val body = response.body ?: run {
                    _runState.value = RunState.FailedToRun("Project Failed to Run", "Empty response from server")
                    return@launch
                }

                // Check if it's a zip or direct file
                val contentType = response.header("Content-Type") ?: ""
                val isZip = url.endsWith(".zip") || contentType.contains("zip") || contentType.contains("octet-stream")

                if (isZip) {
                    val dir = storageManager.importZipArchive(body.byteStream())
                    scanAndRunProject(dir)
                } else {
                    // Save single file
                    storageManager.clearActiveProjectDir()
                    val filename = url.substringAfterLast("/").substringBefore("?").ifEmpty { "downloaded_project.html" }
                    val targetFile = File(storageManager.activeProjectDir, filename)
                    targetFile.writeBytes(body.bytes())
                    scanAndRunProject(storageManager.activeProjectDir)
                }
            } catch (e: Exception) {
                _runState.value = RunState.FailedToRun("Project Failed to Run", e.message ?: "Download error")
            }
        }
    }

    fun restartProject() {
        // Stop -> Clean -> Rescan -> Detect -> Run -> Display (Section 30)
        viewModelScope.launch(Dispatchers.IO) {
            _runState.value = RunState.Loading("Restarting project...")
            stopProjectInternal()
            scanAndRunProject(storageManager.activeProjectDir)
        }
    }

    fun stopProject() {
        // Stop active project processes (Section 29)
        viewModelScope.launch(Dispatchers.IO) {
            stopProjectInternal()
            terminalManager.appendLine(com.example.runner.model.TerminalLine("[Runner] Project processes stopped."))
        }
    }

    private fun stopProjectInternal() {
        activePythonExecution?.stop()
        activePythonExecution = null
        backendClient.stopBackend()
    }

    fun installPipPackage(packageName: String, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = pipManager.installPackage(packageName)
            onFinished(ok)
        }
    }

    override fun onCleared() {
        stopProjectInternal()
        backendClient.unbindService()
        super.onCleared()
    }
}
