package com.example.runner.pip

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class YtDlpTool(
    private val downloadDir: File,
    private val onLog: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Executes yt-dlp command. Downloads media to downloadDir (/storage/emulated/0/Download/).
     */
    fun execute(args: List<String>): Int {
        if (args.isEmpty()) {
            onLog("Usage: yt-dlp [OPTIONS] URL [URL...]")
            return 1
        }

        val url = args.lastOrNull() ?: ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            onError("ERROR: [yt-dlp] '${url}' is not a valid URL")
            return 1
        }

        onLog("[yt-dlp] Extracting URL: $url")
        onLog("[yt-dlp] Destination folder: ${downloadDir.absolutePath}")

        return try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                onError("ERROR: [yt-dlp] HTTP Error ${response.code}: ${response.message}")
                return 1
            }

            val contentType = response.header("Content-Type") ?: "video/mp4"
            val disposition = response.header("Content-Disposition")
            var filename = "download_${System.currentTimeMillis()}"

            if (disposition != null && disposition.contains("filename=")) {
                filename = disposition.substringAfter("filename=").replace("\"", "").trim()
            } else {
                val ext = when {
                    contentType.contains("mp4") -> ".mp4"
                    contentType.contains("webm") -> ".webm"
                    contentType.contains("audio") || contentType.contains("mpeg") -> ".mp3"
                    contentType.contains("json") -> ".json"
                    else -> ".bin"
                }
                val urlName = url.substringAfterLast("/").substringBefore("?").trim()
                filename = if (urlName.isNotEmpty()) urlName else "$filename$ext"
            }

            if (!downloadDir.exists()) downloadDir.mkdirs()
            val destFile = File(downloadDir, filename)
            onLog("[download] Destination: ${destFile.name}")

            val body = response.body ?: run {
                onError("ERROR: [yt-dlp] Empty response body")
                return 1
            }

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                            if (percent % 20 == 0 && percent != lastPercent) {
                                onLog("[download] $percent% of ${totalBytes / 1024}KiB")
                                lastPercent = percent
                            }
                        }
                    }
                }
            }

            onLog("[download] 100% of ${downloadedBytes / 1024}KiB in ${destFile.absolutePath}")
            onLog("[yt-dlp] Download completed successfully")
            0
        } catch (e: Exception) {
            onError("ERROR: [yt-dlp] Execution failed: ${e.message}")
            1
        }
    }
}
