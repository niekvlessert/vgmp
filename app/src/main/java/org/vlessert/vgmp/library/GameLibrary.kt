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
private val GME_EXTENSIONS = listOf(".nsf", ".nsfe", ".gbs", ".gym", ".hes", ".kss", ".ay", ".sap", ".spc")
private val ALL_AUDIO_EXTENSIONS = VGM_EXTENSIONS + GME_EXTENSIONS

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
     * Load bundled single audio files (NSF, etc.) on first run.
     * assetFiles: list of asset paths like "Shovel_Knight_Music.nsf"
     * This will import files that don't already exist in the library.
     */
    suspend fun loadBundledAudioFilesIfNeeded(context: Context, assetFiles: List<String>) =
        withContext(Dispatchers.IO) {
            for (assetPath in assetFiles) {
                try {
                    val fileName = assetPath.substringAfterLast('/')
                    val gameName = fileName.substringBeforeLast('.')
                    
                    // Check if this game already exists
                    if (db.gameDao().searchGames(gameName).isNotEmpty()) {
                        Log.d(TAG, "Game '$gameName' already exists, skipping bundled asset")
                        continue
                    }
                    
                    // Handle ZIP files differently - they contain multiple tracks
                    if (fileName.endsWith(".zip", ignoreCase = true)) {
                        Log.d(TAG, "Importing bundled ZIP: $fileName")
                        context.assets.open(assetPath).use { stream ->
                            importZip(stream, fileName)
                        }
                    } else {
                        // Copy asset to a temp file then import as single file
                        val tempFile = File(context.cacheDir, fileName)
                        context.assets.open(assetPath).use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        importSingleFile(tempFile)
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load bundled audio file $assetPath", e)
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
                        ALL_AUDIO_EXTENSIONS.any { ext -> name.endsWith(ext, true) } ->
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

        Log.d(TAG, "Imported $zipName: ${vgmFiles.size} audio files, art=${artFile != null}, m3u=${m3uContent != null}")

        if (vgmFiles.isEmpty()) {
            Log.w(TAG, "No audio files found in $zipName")
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
        var soundChips = ""
        sortedVgm.forEachIndexed { idx, vgmFile ->
            val durationSamples = try {
                val samples = VgmEngine.getTrackLengthDirect(vgmFile.absolutePath)
                Log.d(TAG, "Track $idx (${vgmFile.name}): durationSamples=$samples")
                samples
            } catch (e: Exception) { 
                Log.e(TAG, "Failed to get duration for ${vgmFile.name}", e)
                -1L 
            }

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
                        
                        // Get sound chips for VGM files
                        try {
                            val deviceCount = VgmEngine.getDeviceCount()
                            if (deviceCount > 0) {
                                val chipNames = mutableListOf<String>()
                                for (i in 0 until deviceCount) {
                                    val chipName = VgmEngine.getDeviceName(i)
                                    if (chipName.isNotEmpty()) chipNames.add(chipName)
                                }
                                soundChips = chipNames.joinToString(", ")
                            }
                        } catch (e: Exception) { 
                            Log.w(TAG, "Could not get sound chips from ${vgmFile.name}") 
                        }
                        
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
            zipSource = zipName,
            soundChips = soundChips
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
    
    /**
     * Import a single audio file (NSF, VGM, etc.) directly without a ZIP.
     * Creates a game entry with a single track.
     * For multi-track files like NSF, creates multiple track entries.
     */
    suspend fun importSingleFile(file: File): Game? = withContext(Dispatchers.IO) {
        try {
            _importSingleFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "importSingleFile failed for ${file.name}", e)
            null
        }
    }
    
    private suspend fun _importSingleFile(file: File): Game? {
        val fileName = file.nameWithoutExtension
        val gameFolder = File(gamesDir, sanitizeFilename(fileName)).also { it.mkdirs() }
        
        // Copy file to game folder
        val destFile = File(gameFolder, file.name)
        file.copyTo(destFile, overwrite = true)
        
        VgmEngine.setSampleRate(44100)
        var gameName = fileName
        var systemName = ""
        var authorName = ""
        var yearStr = ""
        
        // Check if this is a multi-track file (NSF, GBS, etc.)
        val isMultiTrack = VgmEngine.isMultiTrack(destFile.absolutePath)
        val trackCount = if (isMultiTrack) {
            // Open to get track count
            if (VgmEngine.open(destFile.absolutePath)) {
                val count = VgmEngine.getTrackCount()
                VgmEngine.close()
                count
            } else 1
        } else 1
        
        // Get tags from file
        try {
            if (VgmEngine.open(destFile.absolutePath)) {
                val tags = VgmEngine.parseTags(VgmEngine.getTags())
                if (tags.gameEn.isNotEmpty()) gameName = tags.gameEn
                else if (tags.gameJp.isNotEmpty()) gameName = tags.gameJp
                if (tags.systemEn.isNotEmpty()) systemName = tags.systemEn
                else if (tags.systemJp.isNotEmpty()) systemName = tags.systemJp
                if (tags.authorEn.isNotEmpty()) authorName = tags.authorEn
                else if (tags.authorJp.isNotEmpty()) authorName = tags.authorJp
                yearStr = tags.date
                VgmEngine.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get tags from ${file.name}")
        }
        
        // Check for existing game
        val existingGame = db.gameDao().findByPath(gameFolder.absolutePath)
        if (existingGame != null) {
            val tracks = db.trackDao().getTracksForGame(existingGame.id)
            return Game(existingGame, tracks, null)
        }
        
        // Create game entry
        val tempGameEntity = GameEntity(
            name = gameName,
            system = systemName,
            author = authorName,
            year = yearStr,
            folderPath = gameFolder.absolutePath,
            artPath = "",
            zipSource = file.name
        )
        val gameId = db.gameDao().insertGame(tempGameEntity)
        
        // Create track entries
        val trackEntities = mutableListOf<TrackEntity>()
        
        if (isMultiTrack && trackCount > 1) {
            // Multi-track file (NSF, GBS, etc.)
            for (i in 0 until trackCount) {
                val durationSamples = try {
                    VgmEngine.getTrackLength(destFile.absolutePath, i)
                } catch (e: Exception) { 
                    Log.e(TAG, "Failed to get duration for track $i", e)
                    -1L 
                }
                
                trackEntities.add(TrackEntity(
                    id = 0,
                    gameId = gameId,
                    title = "Track ${i + 1}",
                    filePath = destFile.absolutePath,
                    durationSamples = durationSamples,
                    trackIndex = i,
                    isFavorite = false,
                    subTrackIndex = i
                ))
            }
        } else {
            // Single-track file
            val durationSamples = try {
                VgmEngine.getTrackLengthDirect(destFile.absolutePath)
            } catch (e: Exception) { 
                Log.e(TAG, "Failed to get duration for ${destFile.name}", e)
                -1L 
            }
            
            trackEntities.add(TrackEntity(
                id = 0,
                gameId = gameId,
                title = fileName,
                filePath = destFile.absolutePath,
                durationSamples = durationSamples,
                trackIndex = 0,
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
            artPath = "",
            zipSource = file.name
        )
        db.gameDao().insertGame(gameEntity)
        db.trackDao().insertTracks(trackEntities)
        
        return Game(gameEntity, trackEntities, null)
    }
}
