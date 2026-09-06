package com.example.runner.project

import com.example.runner.model.ProjectInfo
import com.example.runner.model.ProjectType
import com.example.runner.model.RunnerJsonConfig
import org.json.JSONObject
import java.io.File

object ProjectDetector {

    /**
     * Deterministic Project Detection Priority:
     * 1. IF valid runner.json exists -> use runner.json
     * 2. ELIF index.html exists AND app2.py exists -> HTML + Python Backend
     * 3. ELIF index.html exists -> HTML
     * 4. ELIF clear Python entry point exists (e.g. main.py) -> Python
     * 5. ELSE IF folder is empty -> EMPTY
     * 6. ELSE -> UNSUPPORTED
     */
    fun detect(projectDir: File): ProjectInfo {
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return ProjectInfo(
                projectDir = projectDir,
                type = ProjectType.EMPTY
            )
        }

        val allFiles = projectDir.listFiles() ?: emptyArray()
        val nonHiddenFiles = allFiles.filter { !it.name.startsWith(".") }

        if (nonHiddenFiles.isEmpty()) {
            return ProjectInfo(
                projectDir = projectDir,
                type = ProjectType.EMPTY
            )
        }

        val requirementsFile = File(projectDir, "requirements.txt").takeIf { it.exists() && it.isFile }
        val runnerJsonFile = File(projectDir, "runner.json").takeIf { it.exists() && it.isFile }

        // 1. Check runner.json
        if (runnerJsonFile != null) {
            val parsedConfig = parseRunnerJson(runnerJsonFile)
            if (parsedConfig != null) {
                val configuredType = parsedConfig.type?.trim()?.lowercase()
                val entryName = parsedConfig.entry?.trim() ?: parsedConfig.html?.trim()
                val backendName = parsedConfig.backend?.trim()

                val htmlCandidate = if (!entryName.isNullOrEmpty()) {
                    File(projectDir, entryName).takeIf { it.exists() } ?: File(projectDir, "index.html").takeIf { it.exists() }
                } else {
                    File(projectDir, "index.html").takeIf { it.exists() }
                }

                val backendCandidate = if (!backendName.isNullOrEmpty()) {
                    File(projectDir, backendName).takeIf { it.exists() }
                } else {
                    File(projectDir, "app2.py").takeIf { it.exists() }
                }

                if (configuredType == "html+python" || configuredType == "html_python" || configuredType == "hybrid" ||
                    (backendCandidate != null && htmlCandidate != null && configuredType != "python")
                ) {
                    if (htmlCandidate != null && htmlCandidate.exists()) {
                        return ProjectInfo(
                            projectDir = projectDir,
                            type = ProjectType.HTML_PYTHON_BACKEND,
                            htmlEntry = htmlCandidate,
                            backendEntry = backendCandidate,
                            requirementsFile = requirementsFile,
                            runnerJsonFile = runnerJsonFile
                        )
                    }
                }

                when (configuredType) {
                    "html" -> {
                        if (htmlCandidate != null && htmlCandidate.exists()) {
                            return if (backendCandidate != null && backendCandidate.exists()) {
                                ProjectInfo(
                                    projectDir = projectDir,
                                    type = ProjectType.HTML_PYTHON_BACKEND,
                                    htmlEntry = htmlCandidate,
                                    backendEntry = backendCandidate,
                                    requirementsFile = requirementsFile,
                                    runnerJsonFile = runnerJsonFile
                                )
                            } else {
                                ProjectInfo(
                                    projectDir = projectDir,
                                    type = ProjectType.HTML,
                                    htmlEntry = htmlCandidate,
                                    requirementsFile = requirementsFile,
                                    runnerJsonFile = runnerJsonFile
                                )
                            }
                        }
                    }
                    "python" -> {
                        val pyEntry = if (!entryName.isNullOrEmpty()) {
                            File(projectDir, entryName).takeIf { it.exists() }
                        } else {
                            findClearPythonEntryPoint(projectDir)
                        }

                        if (pyEntry != null && pyEntry.exists()) {
                            return ProjectInfo(
                                projectDir = projectDir,
                                type = ProjectType.PYTHON,
                                pythonEntry = pyEntry,
                                requirementsFile = requirementsFile,
                                runnerJsonFile = runnerJsonFile
                            )
                        }
                    }
                }
            }
        }

        // 2. ELIF index.html exists AND app2.py exists -> HTML + Python Backend
        val indexHtml = File(projectDir, "index.html")
        val app2Py = File(projectDir, "app2.py")

        if (indexHtml.exists() && indexHtml.isFile && app2Py.exists() && app2Py.isFile) {
            return ProjectInfo(
                projectDir = projectDir,
                type = ProjectType.HTML_PYTHON_BACKEND,
                htmlEntry = indexHtml,
                backendEntry = app2Py,
                requirementsFile = requirementsFile
            )
        }

        // 3. ELIF index.html exists -> HTML
        if (indexHtml.exists() && indexHtml.isFile) {
            return ProjectInfo(
                projectDir = projectDir,
                type = ProjectType.HTML,
                htmlEntry = indexHtml,
                requirementsFile = requirementsFile
            )
        }

        // 4. ELIF clear Python entry point exists -> Python
        val pythonEntryPoint = findClearPythonEntryPoint(projectDir)
        if (pythonEntryPoint != null) {
            return ProjectInfo(
                projectDir = projectDir,
                type = ProjectType.PYTHON,
                pythonEntry = pythonEntryPoint,
                requirementsFile = requirementsFile
            )
        }

        // 5. Otherwise Unsupported
        return ProjectInfo(
            projectDir = projectDir,
            type = ProjectType.UNSUPPORTED,
            requirementsFile = requirementsFile
        )
    }

    private fun findClearPythonEntryPoint(projectDir: File): File? {
        val mainPy = File(projectDir, "main.py")
        if (mainPy.exists() && mainPy.isFile) {
            return mainPy
        }

        val runPy = File(projectDir, "run.py")
        if (runPy.exists() && runPy.isFile) {
            return runPy
        }

        val appPy = File(projectDir, "app.py")
        if (appPy.exists() && appPy.isFile) {
            return appPy
        }

        // Do NOT blindly choose arbitrary .py files
        return null
    }

    private fun parseRunnerJson(file: File): RunnerJsonConfig? {
        return try {
            val content = file.readText()
            val json = JSONObject(content)
            RunnerJsonConfig(
                type = if (json.has("type")) json.getString("type") else null,
                entry = if (json.has("entry")) json.getString("entry") else null,
                html = if (json.has("html")) json.getString("html") else null,
                backend = if (json.has("backend")) json.getString("backend") else null
            )
        } catch (_: Exception) {
            null
        }
    }
}
