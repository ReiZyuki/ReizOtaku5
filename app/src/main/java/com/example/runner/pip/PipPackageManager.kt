package com.example.runner.pip

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PipPackageManager(
    val pipPackagesDir: File,
    private val onLog: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        if (!pipPackagesDir.exists()) {
            pipPackagesDir.mkdirs()
        }
    }

    /**
     * Lists currently installed packages in pip_packages directory.
     */
    fun listInstalledPackages(): List<String> {
        val files = pipPackagesDir.listFiles() ?: return emptyList()
        val result = mutableListOf<String>()
        for (f in files) {
            if (f.name.endsWith(".dist-info") || f.name.endsWith(".egg-info")) {
                val pkgName = f.name.substringBefore(".dist-info").substringBefore(".egg-info")
                result.add(pkgName)
            } else if (f.isDirectory && !f.name.startsWith("__")) {
                result.add(f.name)
            } else if (f.isFile && f.name.endsWith(".py")) {
                result.add(f.name.substringBefore(".py"))
            }
        }
        return result.distinct().sorted()
    }

    fun isPackageInstalled(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase().replace("-", "_")
        val files = pipPackagesDir.listFiles() ?: return false
        return files.any { f ->
            val name = f.name.lowercase().replace("-", "_")
            name == normalized ||
                    name == "$normalized.py" ||
                    name.startsWith("$normalized-") ||
                    name.startsWith("${normalized}_")
        }
    }

    /**
     * Installs packages listed in requirements.txt.
     */
    fun installRequirements(requirementsFile: File): Boolean {
        if (!requirementsFile.exists()) return true
        onLog("[pip] Processing ${requirementsFile.name}...")
        val lines = requirementsFile.readLines()
        var allSuccess = true
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            // Parse package name (e.g. requests>=2.25.0 -> requests)
            val pkgName = trimmed.split("==", ">=", "<=", ">", "<", "~=", ";")[0].trim()
            if (pkgName.isEmpty()) continue

            if (isPackageInstalled(pkgName)) {
                onLog("Requirement already satisfied: $pkgName")
            } else {
                val ok = installPackage(pkgName)
                if (!ok) allSuccess = false
            }
        }
        return allSuccess
    }

    /**
     * Installs a package from PyPI into pipPackagesDir.
     */
    fun installPackage(packageName: String): Boolean {
        val cleanName = packageName.trim().split("==", ">=", "<=", ">", "<", "~=")[0].trim()
        if (cleanName.isEmpty()) return false

        onLog("Collecting $cleanName...")
        val pypiUrl = "https://pypi.org/pypi/$cleanName/json"

        val request = Request.Builder().url(pypiUrl).get().build()
        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = "ERROR: Could not find a version that satisfies the requirement $cleanName (from versions: none)\nERROR: No matching distribution found for $cleanName"
                onError(err)
                return false
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val info = json.getJSONObject("info")
            val version = info.optString("version", "unknown")
            val urls = json.optJSONArray("urls")

            if (urls == null || urls.length() == 0) {
                val err = "ERROR: No download distributions found for $cleanName-$version"
                onError(err)
                return false
            }

            // Find best candidate: pure Python wheel (py3-none-any or py2.py3-none-any) or any wheel
            var downloadUrl: String? = null
            var filename = ""

            for (i in 0 until urls.length()) {
                val fileObj = urls.getJSONObject(i)
                val fname = fileObj.optString("filename", "")
                val packagetype = fileObj.optString("packagetype", "")
                if (packagetype == "bdist_wheel" && (fname.contains("py3-none-any") || fname.contains("py2.py3-none-any") || fname.endsWith(".whl"))) {
                    downloadUrl = fileObj.optString("url")
                    filename = fname
                    break
                }
            }

            // Fallback to first available download
            if (downloadUrl == null) {
                val fileObj = urls.getJSONObject(0)
                downloadUrl = fileObj.optString("url")
                filename = fileObj.optString("filename", "$cleanName-$version.whl")
            }

            onLog("Downloading $filename from PyPI...")
            val dlRequest = Request.Builder().url(downloadUrl).get().build()
            val dlResponse = httpClient.newCall(dlRequest).execute()
            if (!dlResponse.isSuccessful) {
                val err = "ERROR: Failed to download $filename (HTTP ${dlResponse.code})"
                onError(err)
                return false
            }

            val dlBody = dlResponse.body ?: run {
                onError("ERROR: Empty response body for $filename")
                return false
            }

            onLog("Installing collected package: $cleanName-$version...")

            // Extract wheel (wheel files are ZIP archives)
            dlBody.byteStream().use { input ->
                val zipIn = ZipInputStream(input)
                var entry: ZipEntry? = zipIn.nextEntry
                val canonicalTarget = pipPackagesDir.canonicalFile

                while (entry != null) {
                    val entryFile = File(pipPackagesDir, entry.name).canonicalFile
                    // Zip Slip protection
                    if (!entryFile.path.startsWith(canonicalTarget.path + File.separator) && entryFile.path != canonicalTarget.path) {
                        throw SecurityException("Path traversal attempt in wheel entry: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        FileOutputStream(entryFile).use { out ->
                            zipIn.copyTo(out)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            onLog("Successfully installed $cleanName-$version in ${pipPackagesDir.name}")
            true
        } catch (e: Exception) {
            val err = "ERROR: Failed to install $cleanName: ${e.message}"
            onError(err)
            false
        }
    }
}
