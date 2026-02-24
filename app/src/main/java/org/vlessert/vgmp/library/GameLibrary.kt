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
private val GME_EXTENSIONS = listOf(".nsf", ".nsfe", ".gbs", ".gym", ".hes", ".ay", ".sap", ".spc")
// KSS files are now handled by libkss, not libgme
private val KSS_EXTENSIONS = listOf(".kss", ".mgs", ".bgm", ".opx", ".mpk", ".mbm")
private val TRACKER_EXTENSIONS = listOf(".mod", ".xm", ".s3m", ".it", ".mptm", ".stm", ".far", ".ult", ".med", ".mtm", ".psm", ".amf", ".okt", ".dsm", ".dtm", ".umx")
private val ALL_AUDIO_EXTENSIONS = VGM_EXTENSIONS + GME_EXTENSIONS + KSS_EXTENSIONS + TRACKER_EXTENSIONS
private const val TRACKER_GAME_NAME = "Tracker files"

// Data class for vigamup gameinfo
data class VigamupGameInfo(
    val tracksToPlay: List<Int> = emptyList(),
    val vendor: String = "",
    val year: String = ""
)

// Data class for vigamup trackinfo
data class VigamupTrackInfo(
    val trackId: Int,
    val title: String,
    val durationSeconds: Int,
    val loopPoint: Int,
    val repeat: Boolean
)

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
        
        // Vigamup format support
        val gameInfoFiles = mutableMapOf<String, File>()  // baseName -> gameinfo file
        val trackInfoFiles = mutableMapOf<String, File>() // baseName -> trackinfo file
        val artFiles = mutableMapOf<String, File>()       // baseName -> png file
        val kssFiles = mutableMapOf<String, File>()       // baseName -> kss file

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    Log.d(TAG, "Extracting entry: ${entry.name} -> $name")
                    val outFile = File(gameFolder, sanitizeFilename(name))
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    
                    // Check for vigamup format files
                    val baseName = name.substringBeforeLast('.')
                    when {
                        name.endsWith(".gameinfo", true) -> gameInfoFiles[baseName] = outFile
                        name.endsWith(".trackinfo", true) -> trackInfoFiles[baseName] = outFile
                        name.endsWith(".kss", true) || name.endsWith(".mgs", true) || 
                        name.endsWith(".bgm", true) || name.endsWith(".opx", true) ||
                        name.endsWith(".mpk", true) || name.endsWith(".mbm", true) -> {
                            kssFiles[baseName] = outFile
                            vgmFiles.add(outFile)
                        }
                        name.endsWith(".png", true) -> {
                            artFiles[baseName] = outFile
                            artFile = outFile  // Also set for non-vigamup format
                        }
                        ALL_AUDIO_EXTENSIONS.any { ext -> name.endsWith(ext, true) } ->
                            vgmFiles.add(outFile)
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
        Log.d(TAG, "Vigamup: ${kssFiles.size} KSS files, ${gameInfoFiles.size} gameinfo, ${trackInfoFiles.size} trackinfo")

        if (vgmFiles.isEmpty()) {
            Log.w(TAG, "No audio files found in $zipName")
            return null
        }

        // Check if this is a vigamup format (has gameinfo files)
        val isVigamupFormat = gameInfoFiles.isNotEmpty() || trackInfoFiles.isNotEmpty()
        
        if (isVigamupFormat && kssFiles.isNotEmpty()) {
            // Handle vigamup format - each KSS file is a separate game
            return importVigamupGames(zipName, gameFolder, kssFiles, gameInfoFiles, trackInfoFiles, artFiles)
        }
        
        // Handle KSS files without gameinfo (each KSS file is a separate game)
        if (kssFiles.isNotEmpty()) {
            return importKssGames(zipName, gameFolder, kssFiles, artFiles)
        }

        // Standard VGM/VGZ format handling
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

    // Import vigamup format - each KSS file is a separate game with its own metadata
    private suspend fun importVigamupGames(
        zipName: String,
        gameFolder: File,
        kssFiles: Map<String, File>,
        gameInfoFiles: Map<String, File>,
        trackInfoFiles: Map<String, File>,
        artFiles: Map<String, File>
    ): Game? {
        val importedGames = mutableListOf<Game>()
        
        // Process each KSS file as a separate game
        for ((baseName, kssFile) in kssFiles) {
            // Parse gameinfo if available
            val gameInfo = gameInfoFiles[baseName]?.let { file ->
                parseGameInfo(file.readText())
            } ?: VigamupGameInfo()
            
            // Parse trackinfo if available
            val trackInfoList = trackInfoFiles[baseName]?.let { file ->
                parseTrackInfo(file.readText())
            } ?: emptyList()
            val trackInfoMap = trackInfoList.associateBy { it.trackId }
            
            // Get art file for this game
            val artFile = artFiles[baseName]
            
            // Create game name from base name (convert underscores to spaces, title case)
            val gameName = baseName.replace("_", " ").replace("-", " ")
                .split(" ").joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.uppercase() }
                }
            
            // Check if game already exists by KSS file path
            val existingGame = db.gameDao().findByPath(kssFile.absolutePath)
            if (existingGame != null) {
                val tracks = db.trackDao().getTracksForGame(existingGame.id)
                val artBytes = if (artFile?.exists() == true) artFile.readBytes() else null
                importedGames.add(Game(existingGame, tracks, artBytes))
                continue
            }
            
            // Create game entity - use KSS file path as unique folder path
            val gameEntity = GameEntity(
                name = gameName,
                system = "MSX",  // KSS is primarily MSX format
                author = gameInfo.vendor,
                year = gameInfo.year,
                folderPath = kssFile.absolutePath,  // Use KSS file path as unique identifier
                artPath = artFile?.absolutePath ?: "",
                zipSource = zipName,
                soundChips = "KSS"
            )
            val gameId = db.gameDao().insertGame(gameEntity)
            
            // Get KSS track info from native engine
            VgmEngine.setSampleRate(44100)
            val trackEntities = mutableListOf<TrackEntity>()
            
            // Determine which tracks to include
            val tracksToInclude = if (gameInfo.tracksToPlay.isNotEmpty()) {
                gameInfo.tracksToPlay
            } else {
                // If no tracks_to_play specified, get all tracks from KSS
                try {
                    val trackRange = VgmEngine.getKssTrackRange(kssFile.absolutePath)
                    (trackRange[0]..trackRange[1]).toList()
                } catch (e: Exception) {
                    listOf(1)  // Default to track 1
                }
            }
            
            // Create track entities
            tracksToInclude.forEachIndexed { idx, trackId ->
                val trackInfo = trackInfoMap[trackId]
                val durationSamples = trackInfo?.let { info ->
                    // Calculate duration from trackinfo (intro + loop*3 for repeating tracks)
                    val durationMs = calculateTrackDurationMs(info)
                    durationMs * 44100L / 1000L  // Convert ms to samples
                } ?: run {
                    // Try to get duration from native engine
                    try {
                        VgmEngine.getTrackLengthDirect(kssFile.absolutePath)
                    } catch (e: Exception) {
                        -1L
                    }
                }
                
                val title = trackInfo?.title ?: "Track $trackId"
                
                trackEntities.add(TrackEntity(
                    id = 0,
                    gameId = gameId,
                    title = title,
                    filePath = kssFile.absolutePath,
                    durationSamples = durationSamples,
                    trackIndex = idx,
                    isFavorite = false,
                    subTrackIndex = trackId  // Store KSS sub-track index
                ))
            }
            
            db.trackDao().insertTracks(trackEntities)
            
            val artBytes = if (artFile?.exists() == true) artFile.readBytes() else null
            importedGames.add(Game(gameEntity.copy(id = gameId), trackEntities, artBytes))
        }
        
        // Return first game (for compatibility with single-game return type)
        return importedGames.firstOrNull()
    }
    
    // Import KSS files without gameinfo (each KSS file is a separate game)
    private suspend fun importKssGames(
        zipName: String,
        gameFolder: File,
        kssFiles: Map<String, File>,
        artFiles: Map<String, File>
    ): Game? {
        val importedGames = mutableListOf<Game>()
        
        // Process each KSS file as a separate game
        for ((baseName, kssFile) in kssFiles) {
            // Get art file for this game
            val artFile = artFiles[baseName]
            
            // Create game name from base name (convert underscores to spaces, title case)
            val gameName = baseName.replace("_", " ").replace("-", " ")
                .split(" ").joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.uppercase() }
                }
            
            // Check if game already exists by KSS file path
            val existingGame = db.gameDao().findByPath(kssFile.absolutePath)
            if (existingGame != null) {
                val tracks = db.trackDao().getTracksForGame(existingGame.id)
                val artBytes = if (artFile?.exists() == true) artFile.readBytes() else null
                importedGames.add(Game(existingGame, tracks, artBytes))
                continue
            }
            
            // Create game entity - use KSS file path as unique folder path
            val gameEntity = GameEntity(
                name = gameName,
                system = "MSX",  // KSS is primarily MSX format
                author = "",
                year = "",
                folderPath = kssFile.absolutePath,  // Use KSS file path as unique identifier
                artPath = artFile?.absolutePath ?: "",
                zipSource = zipName,
                soundChips = "KSS"
            )
            val gameId = db.gameDao().insertGame(gameEntity)
            
            // Get KSS track info from native engine
            VgmEngine.setSampleRate(44100)
            val trackEntities = mutableListOf<TrackEntity>()
            
            // Get all tracks from KSS
            val trackRange = try {
                VgmEngine.getKssTrackRange(kssFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get KSS track range for ${kssFile.name}", e)
                intArrayOf(1, 1)
            }
            
            val tracksToInclude = (trackRange[0]..trackRange[1]).toList()
            Log.d(TAG, "KSS file ${kssFile.name}: ${tracksToInclude.size} tracks (${trackRange[0]}-${trackRange[1]})")
            
            // Create track entities
            tracksToInclude.forEachIndexed { idx, trackId ->
                val durationSamples = try {
                    VgmEngine.getTrackLengthDirect(kssFile.absolutePath)
                } catch (e: Exception) {
                    -1L
                }
                
                val title = "Track $trackId"
                
                trackEntities.add(TrackEntity(
                    id = 0,
                    gameId = gameId,
                    title = title,
                    filePath = kssFile.absolutePath,
                    durationSamples = durationSamples,
                    trackIndex = idx,
                    isFavorite = false,
                    subTrackIndex = trackId  // Store KSS sub-track index
                ))
            }
            
            db.trackDao().insertTracks(trackEntities)
            
            val artBytes = if (artFile?.exists() == true) artFile.readBytes() else null
            importedGames.add(Game(gameEntity.copy(id = gameId), trackEntities, artBytes))
        }
        
        // Return first game (for compatibility with single-game return type)
        return importedGames.firstOrNull()
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

    // Parse vigamup gameinfo file
    // Format: "key:value" lines, e.g., "tracks_to_play:2,3,4,5,6,7,8,10,14,15,16"
    private fun parseGameInfo(content: String): VigamupGameInfo {
        var tracksToPlay = emptyList<Int>()
        var vendor = ""
        var year = ""
        
        content.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()
                when (key) {
                    "tracks_to_play" -> {
                        tracksToPlay = value.split(",")
                            .mapNotNull { it.trim().toIntOrNull() }
                    }
                    "vendor" -> vendor = value
                    "year" -> year = value
                }
            }
        }
        
        return VigamupGameInfo(tracksToPlay, vendor, year)
    }

    // Parse vigamup trackinfo file
    // Format: "track_id,title,duration_seconds,loop_point,repeat_yes/no"
    private fun parseTrackInfo(content: String): List<VigamupTrackInfo> {
        return content.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size >= 5) {
                    val trackId = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val title = parts[1].trim()
                    val durationSeconds = parts[2].trim().toIntOrNull() ?: 0
                    val loopPoint = parts[3].trim().toIntOrNull() ?: 0
                    val repeat = parts[4].trim().lowercase() == "y"
                    VigamupTrackInfo(trackId, title, durationSeconds, loopPoint, repeat)
                } else null
            }
    }

    // Calculate duration for a track with loop info
    // For repeating tracks: intro + (loop_duration * 3)
    // For non-repeating: just the duration
    private fun calculateTrackDurationMs(trackInfo: VigamupTrackInfo?): Long {
        if (trackInfo == null || trackInfo.durationSeconds <= 0) {
            return 180000L // Default 3 minutes
        }
        
        val introMs = trackInfo.durationSeconds * 1000L
        val loopMs = trackInfo.loopPoint * 1000L
        
        return if (trackInfo.repeat && loopMs > 0) {
            // For repeating tracks: intro + 3 * loop
            introMs + loopMs * 3
        } else {
            introMs
        }
    }

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
    
    /**
     * Import a tracker file (MOD, XM, S3M, IT, etc.) into the special "Tracker files" game.
     * Tracker files are grouped together under a single game entry.
     */
    suspend fun importTrackerFile(file: File): Game? = withContext(Dispatchers.IO) {
        try {
            _importTrackerFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "importTrackerFile failed for ${file.name}", e)
            null
        }
    }
    
    private suspend fun _importTrackerFile(file: File): Game? {
        // Get or create the "Tracker files" game entry
        var trackerGame = db.gameDao().searchGames(TRACKER_GAME_NAME).firstOrNull()
        
        val trackerFolder = File(gamesDir, sanitizeFilename(TRACKER_GAME_NAME)).also { it.mkdirs() }
        
        // Copy file to tracker folder
        val destFile = File(trackerFolder, file.name)
        file.copyTo(destFile, overwrite = true)
        
        VgmEngine.setSampleRate(44100)
        
        // Get tags from tracker file
        var trackTitle = file.nameWithoutExtension
        var authorName = ""
        var systemName = "Tracker"
        
        try {
            if (VgmEngine.open(destFile.absolutePath)) {
                val tags = VgmEngine.parseTags(VgmEngine.getTags())
                if (tags.trackEn.isNotEmpty()) trackTitle = tags.trackEn
                if (tags.authorEn.isNotEmpty()) authorName = tags.authorEn
                VgmEngine.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get tags from tracker file ${file.name}")
        }
        
        // Get duration (tracker files default to 3 minutes in the engine)
        val durationSamples = try {
            VgmEngine.getTrackLengthDirect(destFile.absolutePath)
        } catch (e: Exception) { 
            Log.e(TAG, "Failed to get duration for ${destFile.name}", e)
            -1L 
        }
        
        if (trackerGame == null) {
            // Create new "Tracker files" game
            val gameEntity = GameEntity(
                name = TRACKER_GAME_NAME,
                system = "Various",
                author = "",
                year = "",
                folderPath = trackerFolder.absolutePath,
                artPath = "",
                zipSource = "tracker_files"
            )
            val gameId = db.gameDao().insertGame(gameEntity)
            
            val trackEntity = TrackEntity(
                id = 0,
                gameId = gameId,
                title = trackTitle,
                filePath = destFile.absolutePath,
                durationSamples = durationSamples,
                trackIndex = 0,
                isFavorite = false
            )
            db.trackDao().insertTrack(trackEntity)
            
            return Game(gameEntity.copy(id = gameId), listOf(trackEntity), null)
        } else {
            // Add to existing "Tracker files" game
            val existingTracks = db.trackDao().getTracksForGame(trackerGame.id)
            val nextIndex = existingTracks.size
            
            val trackEntity = TrackEntity(
                id = 0,
                gameId = trackerGame.id,
                title = trackTitle,
                filePath = destFile.absolutePath,
                durationSamples = durationSamples,
                trackIndex = nextIndex,
                isFavorite = false
            )
            db.trackDao().insertTrack(trackEntity)
            
            val allTracks = db.trackDao().getTracksForGame(trackerGame.id)
            return Game(trackerGame, allTracks, null)
        }
    }
    
    /**
     * Load bundled tracker files from assets on first run.
     * trackerFiles: list of asset paths like "ophelias_charm.it"
     */
    suspend fun loadBundledTrackerFilesIfNeeded(context: Context, trackerFiles: List<String>) =
        withContext(Dispatchers.IO) {
            // Check if "Tracker files" game already exists
            if (db.gameDao().searchGames(TRACKER_GAME_NAME).isNotEmpty()) {
                Log.d(TAG, "Tracker files game already exists, skipping bundled assets")
                return@withContext
            }
            
            for (assetPath in trackerFiles) {
                try {
                    val fileName = assetPath.substringAfterLast('/')
                    val tempFile = File(context.cacheDir, fileName)
                    context.assets.open(assetPath).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    importTrackerFile(tempFile)
                    tempFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load bundled tracker file $assetPath", e)
                }
            }
        }
}
