package org.vlessert.vgmp.download

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.library.Game
import org.vlessert.vgmp.library.GameLibrary
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "DownloadWorker"

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        // Convert HTTP to HTTPS (Android requires HTTPS by default)
        val url = if (rawUrl.startsWith("http://")) rawUrl.replace("http://", "https://") else rawUrl
        val zipName = inputData.getString(KEY_NAME) ?: url.substringAfterLast('/')

        try {
            setProgress(workDataOf(KEY_PROGRESS to 0, KEY_STATUS to "Connecting..."))
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                Log.e(TAG, "Server returned error: $responseCode ${conn.responseMessage} for $url")
                return@withContext Result.failure(workDataOf(KEY_STATUS to "Server error: $responseCode"))
            }

            val contentLength = conn.contentLength
            setProgress(workDataOf(KEY_PROGRESS to 0, KEY_STATUS to "Downloading..."))

            conn.inputStream.use { input ->
                val buffered = object : java.io.InputStream() {
                    private val buf = BufferedInputStream(input, 65536)
                    private var read = 0L
                    override fun read() = buf.read().also { if (it >= 0) updateProgress(1L, contentLength) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = buf.read(b, off, len)
                        if (n > 0) updateProgress(n.toLong(), contentLength)
                        return n
                    }

                    private fun updateProgress(bytes: Long, total: Int) {
                        read += bytes
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            setProgressAsync(workDataOf(KEY_PROGRESS to pct, KEY_STATUS to "Downloading..."))
                        }
                    }
                }

                GameLibrary.init(applicationContext)
                val game = GameLibrary.importZip(buffered, zipName)
                if (game != null) {
                    // For KSS collections, the game name might be just the first game
                    // Show the zip name instead for clarity
                    val displayName = if (zipName.contains("Konami-SCC-Collection", ignoreCase = true)) {
                        "Konami SCC Collection"
                    } else {
                        game.name
                    }
                    setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS to "Done: $displayName"))
                    Result.success(workDataOf(KEY_GAME_NAME to displayName))
                } else {
                    Result.failure(workDataOf(KEY_STATUS to "No VGM files found in ZIP"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $url", e)
            Result.failure(workDataOf(KEY_STATUS to "Error: ${e.message}"))
        }
    }

    companion object {
        const val KEY_URL      = "url"
        const val KEY_NAME     = "name"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS   = "status"
        const val KEY_GAME_NAME = "game_name"

        fun enqueue(context: Context, url: String, name: String): androidx.work.WorkRequest {
            val data = workDataOf(KEY_URL to url, KEY_NAME to name)
            return OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .addTag("download_$name")
                .build()
        }
    }
}

/** Preset download sources */
data class DownloadSource(
    val name: String,
    val description: String,
    val url: String,
    val fileType: String = ".zip"
)

object DownloadSources {
    val presets = listOf(
        DownloadSource(
            name = "Konami SCC Collection (KSS)",
            description = "MSX music from Konami games",
            url = "https://vlessert.nl/vigamup/vigamup_kss_Konami-SCC-Collection.zip",
            fileType = ".zip"
        )
    )
}
