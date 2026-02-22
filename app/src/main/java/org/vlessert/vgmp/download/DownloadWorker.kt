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
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val zipName = inputData.getString(KEY_NAME) ?: url.substringAfterLast('/')

        try {
            setProgress(workDataOf(KEY_PROGRESS to 0, KEY_STATUS to "Connecting..."))
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connect()
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
                    setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS to "Done: ${game.name}"))
                    Result.success(workDataOf(KEY_GAME_NAME to game.name))
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
            name = "Project 2612 Complete Archive",
            description = "681 Sega Genesis/Mega Drive game sets",
            url = "https://archive.org/download/Project2612CompleteArchive20180623681Sets.7z/Project2612%20Complete%20Archive%20%282018-06-23%29%5B681%20sets%5D.7z",
            fileType = ".7z"
        ),
        DownloadSource(
            name = "VGMRips SMD Pack (Sample)",
            description = "Sample Sega Mega Drive VGM tracks (zip)",
            url = "https://vgmrips.net/packs/pack/sonic-the-hedgehog",
            fileType = ".zip"
        ),
        DownloadSource(
            name = "Sonic the Hedgehog (MD)",
            description = "Sonic 1 VGM rip – Mega Drive",
            url = "https://archive.org/download/vgm-packs-sonic/Sonic_the_Hedgehog_MD.zip",
            fileType = ".zip"
        ),
        DownloadSource(
            name = "Streets of Rage (MD)",
            description = "Streets of Rage VGM – Mega Drive",
            url = "https://archive.org/download/vgm-packs-sor/Streets_of_Rage_MD.zip",
            fileType = ".zip"
        ),
    )
}
