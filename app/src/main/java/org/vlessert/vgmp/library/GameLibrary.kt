package org.vlessert.vgmp.library

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.engine.VgmEngine
import java.io.*
import java.util.zip.ZipInputStream

private const val TAG = "GameLibrary"
private const val MAX_SEARCH_RESULTS = 50
private val VGM_EXTENSIONS = listOf(".vgm", ".vgz")

/** In-memory representation of a loaded game (used in UI / service) */
data class Game(
    val entity: GameEntity,
    val tracks: List<TrackEntity>,
    val artBytes: ByteArray? = null   // PNG bytes for album art
) {
    val id get() = entity.id
    val name get() = entity.name
    val system get() = entity.system
    val artPath get() = entity.artPath
}

/**
 * Singleton that manages the VGM game library:
 * - Extracts bundled assets + user-downloaded ZIPs
 * - Indexes into Room DB
 * - Provides search
 */
object GameLibrary {

    private lateinit var db: VgmDatabase
    private lateinit var gamesDir: File
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        db = VgmDatabase.getInstance(context)
        gamesDir = File(context.filesDir, "games").also { it.mkdirs() }
        initialized = true
    }

    /**
     * Load bundled asset ZIPs on first run (only if DB is empty).
     * assetZips: list of asset paths like "music/sonic.zip"
     */
    suspend fun loadBundledAssetsIfNeeded(context: Context, assetZips: List<String>) =
        withContext(Dispatchers.IO) {
            if (db.gameDao().count() > 0) return@withContext
            for (assetPath in assetZips) {
                try {
                    context.assets.open(assetPath).use { stream ->
                        importZip(stream, assetPath.substringAfterLast('/'))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load bundled asset $assetPath", e)
                }
            }
        }

    /**
     * Import a ZIP file (from any InputStream) into the library.
     * Returns the created GameEntity on success, null on failure.
     */
    suspend fun importZip(inputStream: InputStream, zipName: String): Game? =
        withContext(Dispatchers.IO) {
            try {
                _importZip(inputStream, zipName)
            } catch (e: Exception) {
                Log.e(TAG, "importZip failed for $zipName", e)
                null
            }
        }

    private suspend fun _importZip(inputStream: InputStream, zipName: String): Game? {
        // Use zip stem as folder name
        val folderName = zipName.removeSuffix(".zip").removeSuffix(".ZIP")
        val gameFolder = File(gamesDir, sanitizeFilename(folderName)).also { it.mkdirs() }

        // Extract files
        val vgmFiles = mutableListOf<File>()
        var artFile: File? = null
        var m3uContent: String? = null

        val m3uTitles = mutableMapOf<String, String>()
        var firstM3uName: String? = null

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    Log.d(TAG, "Extracting entry: ${entry.name} -> $name")
                    val outFile = File(gameFolder, sanitizeFilename(name))
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    when {
                        name.endsWith(".vgm", true) || name.endsWith(".vgz", true) ->
                            vgmFiles.add(outFile)
                        name.endsWith(".png", true) -> artFile = outFile
                        name.endsWith(".m3u", true) -> {
                            if (firstM3uName == null) firstM3uName = name.removeSuffix(".m3u").removeSuffix(".M3U")
                            m3uContent = outFile.readText()
                            // Parse titles: "filename.vgz, Track Title"
                            m3uContent?.lines()?.forEach { line ->
                                if (line.isNotBlank() && !line.startsWith("#")) {
                                    val parts = line.split(",", limit = 2)
                                    if (parts.size == 2) {
                                        val m3uName = parts[0].trim().substringAfterLast('/')
                                        m3uTitles[m3uName] = parts[1].trim()
                                    }
                                }
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        Log.d(TAG, "Imported $zipName: ${vgmFiles.size} VGM files, art=${artFile != null}, m3u=${m3uContent != null}")

        if (vgmFiles.isEmpty()) {
            Log.w(TAG, "No VGM files found in $zipName")
            return null
        }

        // Sort VGM files – respect .m3u order if available
        val sortedVgm = if (m3uContent != null) {
            sortByM3u(vgmFiles, m3uContent!!, gameFolder)
        } else {
            vgmFiles.sortedBy { it.name }
        }

        // Get tags from first track
        VgmEngine.setSampleRate(44100)
        var gameName = folderName
        var systemName = ""
        var authorName = ""
        var yearStr = ""
        val trackEntities = mutableListOf<TrackEntity>()

        // Fallback to m3u folder name if tags aren't found
        if (firstM3uName != null) gameName = firstM3uName!!

        // Insert game into DB first (need ID for tracks)
        val existingGame = db.gameDao().findByPath(gameFolder.absolutePath)
        if (existingGame != null) {
            // Re-use existing game entry
            val tracks = db.trackDao().getTracksForGame(existingGame.id)
            val artBytes = if (artFile?.exists() == true) artFile!!.readBytes() else null
            return Game(existingGame, tracks, artBytes)
        }

        val tempGameEntity = GameEntity(
            name = gameName, system = systemName, author = authorName, year = yearStr,
            folderPath = gameFolder.absolutePath,
            artPath = artFile?.absolutePath ?: "",
            zipSource = zipName
        )
        val gameId = db.gameDao().insertGame(tempGameEntity)

        // Scan tracks for duration + tags
        sortedVgm.forEachIndexed { idx, vgmFile ->
            val durationSamples = try {
                VgmEngine.getTrackLengthDirect(vgmFile.absolutePath)
            } catch (e: Exception) { -1L }

            // Get tags from first track only (for game name)
            if (idx == 0) {
                try {
                    if (VgmEngine.open(vgmFile.absolutePath)) {
                        val tags = VgmEngine.parseTags(VgmEngine.getTags())
                        // Use English game name, fallback to Japanese
                        if (tags.gameEn.isNotEmpty()) gameName = tags.gameEn
                        else if (tags.gameJp.isNotEmpty()) gameName = tags.gameJp
                        // Use English system name, fallback to Japanese
                        if (tags.systemEn.isNotEmpty()) systemName = tags.systemEn
                        else if (tags.systemJp.isNotEmpty()) systemName = tags.systemJp
                        // Use English author name, fallback to Japanese
                        if (tags.authorEn.isNotEmpty()) authorName = tags.authorEn
                        else if (tags.authorJp.isNotEmpty()) authorName = tags.authorJp
                        yearStr = tags.date
                        VgmEngine.close()
                    }
                } catch (e: Exception) { Log.w(TAG, "Could not get tags from ${vgmFile.name}") }
            }

            val originalFilenameForTitle = vgmFile.name
            val displayTitle = m3uTitles[originalFilenameForTitle] 
                ?: m3uTitles.entries.firstOrNull { it.key.equals(originalFilenameForTitle, ignoreCase = true) }?.value 
                ?: vgmFile.nameWithoutExtension

            trackEntities.add(TrackEntity(
                id = 0, // Auto-generated
                gameId = gameId,
                title = displayTitle,
                filePath = vgmFile.absolutePath,
                durationSamples = durationSamples,
                trackIndex = idx,
                isFavorite = false
            ))
        }

        // Update game with resolved name
        val gameEntity = GameEntity(
            id = gameId,
            name = gameName,
            system = systemName,
            author = authorName,
            year = yearStr,
            folderPath = gameFolder.absolutePath,
            artPath = artFile?.absolutePath ?: "",
            zipSource = zipName
        )
        db.gameDao().insertGame(gameEntity)
        db.trackDao().insertTracks(trackEntities)

        val artBytes = if (artFile?.exists() == true) artFile!!.readBytes() else null
        return Game(gameEntity, trackEntities, artBytes)
    }

    private fun sortByM3u(files: List<File>, m3u: String, baseDir: File): List<File> {
        val ordered = m3u.lines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .mapNotNull { ref ->
                val name = ref.trim().substringAfterLast('/')
                files.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        val rest = files.filter { f -> ordered.none { it.absolutePath == f.absolutePath } }
        return ordered + rest
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\-() ]"), "_")

    /** Search games by name substring. Returns max 50 results with tracks loaded.
     * If query is empty, returns all games. Favorites are sorted to the top. */
    suspend fun search(query: String): List<Game> = withContext(Dispatchers.IO) {
        val gameEntities = if (query.isBlank()) {
            db.gameDao().getAllGames()
        } else {
            db.gameDao().searchGames(query)
        }
        // Sort favorites to top, then by name
        val sorted = gameEntities.sortedWith(compareByDescending<GameEntity> { it.isFavorite }.thenBy { it.name.lowercase() })
        sorted.take(MAX_SEARCH_RESULTS).map { gameEntity ->
            val tracks = db.trackDao().getTracksForGame(gameEntity.id)
            // Keep tracks in original order - don't sort to avoid index mismatch with service
            val artBytes = if (gameEntity.artPath.isNotEmpty()) {
                try { File(gameEntity.artPath).readBytes() } catch (e: Exception) { null }
            } else null
            Game(gameEntity, tracks, artBytes)
        }
    }

    suspend fun toggleFavorite(gameId: Long) = withContext(Dispatchers.IO) {
        val game = db.gameDao().getAllGames().firstOrNull { it.id == gameId } ?: return@withContext
        val updated = game.copy(isFavorite = !game.isFavorite)
        db.gameDao().updateGame(updated)
    }

    suspend fun toggleTrackFavorite(trackId: Long) = withContext(Dispatchers.IO) {
        val track = db.trackDao().getTrackById(trackId) ?: return@withContext
        val updated = track.copy(isFavorite = !track.isFavorite)
        db.trackDao().updateTrack(updated)
    }

    suspend fun getTrackById(trackId: Long): TrackEntity? = withContext(Dispatchers.IO) {
        db.trackDao().getTrackById(trackId)
    }

    suspend fun getFavoriteTracks(): List<TrackEntity> = withContext(Dispatchers.IO) {
        db.trackDao().getFavoriteTracks()
    }

    /** Get a specific game with its tracks */
    suspend fun getGame(gameId: Long): Game? = withContext(Dispatchers.IO) {
        val gameEntity = db.gameDao().getAllGames().firstOrNull { it.id == gameId } ?: return@withContext null
        val tracks = db.trackDao().getTracksForGame(gameId)
        val artBytes = if (gameEntity.artPath.isNotEmpty()) {
            try { File(gameEntity.artPath).readBytes() } catch (e: Exception) { null }
        } else null
        Game(gameEntity, tracks, artBytes)
    }

    /** All games (for Android Auto root browse) */
    suspend fun getAllGames(): List<Game> = withContext(Dispatchers.IO) {
        db.gameDao().getAllGames().map { gameEntity ->
            val tracks = db.trackDao().getTracksForGame(gameEntity.id)
            Game(gameEntity, tracks, null)
        }
    }

    /** Get count of games in library */
    suspend fun getGameCount(): Int = withContext(Dispatchers.IO) {
        db.gameDao().count()
    }

    /** Check if a game with the given name exists (case-insensitive partial match) */
    suspend fun gameExists(name: String): Boolean = withContext(Dispatchers.IO) {
        db.gameDao().searchGames(name).isNotEmpty()
    }
}
