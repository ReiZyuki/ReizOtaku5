package com.example.runner.project

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ProjectStorageManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "runner_prefs"
        private const val KEY_PERSISTED_FOLDER_URI = "persisted_folder_uri"
        private const val KEY_PERSISTED_FOLDER_PATH = "persisted_folder_path"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Active project directory where project files reside for native I/O, WebView and Python.
     */
    val activeProjectDir: File = File(context.filesDir, "active_project").apply {
        if (!exists()) mkdirs()
    }

    /**
     * Private Python package directory: context.filesDir/pip_packages
     * (Section 22 & 26: Stored privately, never in global storage)
     */
    val pipPackagesDir: File = File(context.filesDir, "pip_packages").apply {
        if (!exists()) mkdirs()
    }

    /**
     * Normal download directory: /storage/emulated/0/Download/ (Section 27)
     */
    val normalDownloadDir: File
        get() {
            val extDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return if (extDownload != null && extDownload.exists()) {
                extDownload
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "downloads").apply {
                    if (!exists()) mkdirs()
                }
            }
        }

    /**
     * Conceptual default folder: /storage/emulated/0/Download/runner/
     */
    val defaultExternalRunnerDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "runner")

    fun getPersistedFolderUri(): String? {
        return prefs.getString(KEY_PERSISTED_FOLDER_URI, null)
    }

    fun setPersistedFolderUri(uri: String?) {
        prefs.edit().putString(KEY_PERSISTED_FOLDER_URI, uri).apply()
    }

    fun getPersistedFolderPath(): String? {
        return prefs.getString(KEY_PERSISTED_FOLDER_PATH, null)
    }

    fun setPersistedFolderPath(path: String?) {
        prefs.edit().putString(KEY_PERSISTED_FOLDER_PATH, path).apply()
    }

    /**
     * Checks if default / persisted directory exists and prepares the project.
     */
    fun resolveCurrentProjectDir(): File {
        // 1. If user previously selected a SAF folder uri
        val savedUriStr = getPersistedFolderUri()
        if (!savedUriStr.isNullOrEmpty()) {
            try {
                val treeUri = Uri.parse(savedUriStr)
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.exists() && docFile.isDirectory) {
                    syncFromDocumentTree(docFile, activeProjectDir)
                    return activeProjectDir
                }
            } catch (_: Exception) {
                // Ignore SAF access failure and fallback
            }
        }

        // 2. Check if default Download/runner/ exists and has files
        val defaultDir = defaultExternalRunnerDir
        if (defaultDir.exists() && defaultDir.isDirectory) {
            val list = defaultDir.listFiles()
            if (list != null && list.isNotEmpty()) {
                copyDirectory(defaultDir, activeProjectDir)
                return activeProjectDir
            }
        }

        // 3. Check if activeProjectDir has existing files
        val activeFiles = activeProjectDir.listFiles()?.filter { !it.name.startsWith(".") }
        if (!activeFiles.isNullOrEmpty()) {
            return activeProjectDir
        }

        // 4. Return active project dir (which is currently empty)
        return activeProjectDir
    }

    /**
     * Syncs files from a Storage Access Framework DocumentFile tree into the local activeProjectDir.
     */
    fun syncFromDocumentTree(sourceTree: DocumentFile, targetDir: File) {
        clearActiveProjectDir()
        syncDocumentNode(sourceTree, targetDir)
    }

    private fun syncDocumentNode(sourceNode: DocumentFile, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val children = sourceNode.listFiles()
        for (child in children) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue

            if (child.isDirectory) {
                val subDir = File(targetDir, name)
                syncDocumentNode(child, subDir)
            } else if (child.isFile) {
                val targetFile = File(targetDir, name)
                try {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Safely extract a project ZIP stream into activeProjectDir with Zip Slip protection.
     */
    fun importZipArchive(inputStream: InputStream): File {
        clearActiveProjectDir()
        val zipIn = ZipInputStream(inputStream)
        var entry: ZipEntry? = zipIn.nextEntry

        val targetDirCanonical = activeProjectDir.canonicalFile

        while (entry != null) {
            val entryName = entry.name
            // Normalize path
            val outFile = File(activeProjectDir, entryName).canonicalFile

            // Zip Slip protection: ensure target file is strictly inside target directory
            if (!outFile.path.startsWith(targetDirCanonical.path + File.separator) && outFile.path != targetDirCanonical.path) {
                throw SecurityException("ZIP entry attempts path traversal: $entryName")
            }

            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    zipIn.copyTo(output)
                }
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // If the zip contained a single root directory (e.g. repo-main/), hoist its contents
        val extractedRootFiles = activeProjectDir.listFiles()?.filter { !it.name.startsWith(".") }
        if (extractedRootFiles != null && extractedRootFiles.size == 1 && extractedRootFiles[0].isDirectory) {
            val singleDir = extractedRootFiles[0]
            val subFiles = singleDir.listFiles() ?: emptyArray()
            val tempDir = File(context.filesDir, "temp_hoist").apply { mkdirs() }
            for (f in subFiles) {
                f.renameTo(File(tempDir, f.name))
            }
            singleDir.deleteRecursively()
            for (f in tempDir.listFiles() ?: emptyArray()) {
                f.renameTo(File(activeProjectDir, f.name))
            }
            tempDir.deleteRecursively()
        }

        return activeProjectDir
    }

    fun clearActiveProjectDir() {
        activeProjectDir.listFiles()?.forEach {
            it.deleteRecursively()
        }
    }

    private fun copyDirectory(source: File, target: File) {
        if (!target.exists()) target.mkdirs()
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (file.isDirectory) {
                copyDirectory(file, dest)
            } else {
                file.copyTo(dest, overwrite = true)
            }
        }
    }
}
