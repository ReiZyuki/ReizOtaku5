package com.example

import com.example.runner.model.ProjectType
import com.example.runner.project.ProjectDetector
import com.example.runner.python.PythonRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyProjectDetection() {
        val root = tempFolder.newFolder("empty_project")
        val info = ProjectDetector.detect(root)
        assertEquals(ProjectType.EMPTY, info.type)
    }

    @Test
    fun testHtmlProjectDetection() {
        val root = tempFolder.newFolder("html_project")
        File(root, "index.html").writeText("<!DOCTYPE html><html><body><h1>Hello</h1></body></html>")
        val info = ProjectDetector.detect(root)
        assertEquals(ProjectType.HTML, info.type)
        assertNotNull(info.htmlEntry)
    }

    @Test
    fun testHtmlAndPythonBackendDetection() {
        val root = tempFolder.newFolder("hybrid_project")
        File(root, "index.html").writeText("<!DOCTYPE html><html><body><h1>Hybrid</h1></body></html>")
        File(root, "app2.py").writeText("import telebot\nprint('Backend loaded')")
        val info = ProjectDetector.detect(root)
        assertEquals(ProjectType.HTML_PYTHON_BACKEND, info.type)
        assertNotNull(info.htmlEntry)
        assertNotNull(info.backendEntry)
        assertEquals("app2.py", info.backendEntry?.name)
    }

    @Test
    fun testPythonProjectDetection() {
        val root = tempFolder.newFolder("python_project")
        File(root, "main.py").writeText("print('Hello Python')")
        val info = ProjectDetector.detect(root)
        assertEquals(ProjectType.PYTHON, info.type)
        assertNotNull(info.pythonEntry)
    }

    @Test
    fun testRunnerJsonExplicitConfiguration() {
        val root = tempFolder.newFolder("configured_project")
        File(root, "index.html").writeText("<h1>Custom</h1>")
        File(root, "bot.py").writeText("print('custom bot')")
        File(root, "runner.json").writeText("""
            {
                "type": "html+python",
                "html": "index.html",
                "backend": "bot.py"
            }
        """.trimIndent())

        val info = ProjectDetector.detect(root)
        assertEquals(ProjectType.HTML_PYTHON_BACKEND, info.type)
        assertEquals("bot.py", info.backendEntry?.name)
    }

    @Test
    fun testPythonRuntimeExecution() {
        val root = tempFolder.newFolder("py_runtime_test")
        val pipDir = tempFolder.newFolder("pip_dir")
        val outputList = mutableListOf<String>()

        val runtime = PythonRuntime(
            workingDir = root,
            pipPackagesDir = pipDir,
            onOutput = { outputList.add(it) }
        )

        val code = """
            message = "Hello from local Python"
            print(message)
            num = 42
            print(num)
        """.trimIndent()

        val exitCode = runtime.executeCode(code)
        assertEquals(0, exitCode)
        assertEquals("Hello from local Python", outputList[0])
        assertEquals("42", outputList[1])
    }
}

