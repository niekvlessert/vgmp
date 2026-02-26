package org.vlessert.vgmp.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.settings.SettingsManager
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GameLibrary"
private const val MAX_SEARCH_RESULTS = 50
private val VGM_EXTENSIONS = listOf(".vgm", ".vgz")
private val GME_EXTENSIONS = listOf(".nsf", ".nsfe", ".gbs", ".gym", ".hes", ".ay", ".sap", ".spc")
// KSS files are now handled by libkss, not libgme
private val KSS_EXTENSIONS = listOf(".kss", ".mgs", ".bgm", ".opx", ".mpk", ".mbm")
private val TRACKER_EXTENSIONS = listOf(".mod", ".xm", ".s3m", ".it", ".mptm", ".stm", ".far", ".ult", ".med", ".mtm", ".psm", ".amf", ".okt", ".dsm", ".dtm", ".umx")
// MIDI files are handled by libADLMIDI (OPL3 FM synthesis)
private val MIDI_EXTENSIONS = listOf(".mid", ".midi", ".rmi", ".smf")
// MUS files are Doom music files handled by libMusDoom (OPL2/OPL3 FM synthesis)
private val MUS_EXTENSIONS = listOf(".mus", ".lmp")
// RSN files are RAR archives containing SPC files
    private val RAR_ARCHIVE_EXTENSIONS = listOf(".rsn", ".rar")
    private val PSF_EXTENSIONS = listOf(".psf", ".psf1", ".psf2", ".minipsf", ".minipsf1", ".minipsf2")
    private val ALL_AUDIO_EXTENSIONS = VGM_EXTENSIONS + GME_EXTENSIONS + KSS_EXTENSIONS + TRACKER_EXTENSIONS + MIDI_EXTENSIONS + MUS_EXTENSIONS + RAR_ARCHIVE_EXTENSIONS + PSF_EXTENSIONS
private const val TRACKER_GAME_NAME = "Tracker files"
private const val MIDI_GAME_NAME = "MIDI files"
private const val DOOM1_GAME_NAME = "Doom"
private const val DOOM2_GAME_NAME = "Doom II"

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
    val soundChips get() = entity.soundChips
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
    private lateinit var appContext: Context
    private var initialized = false

    private val EXTENSION_GROUPS = mapOf(
        SettingsManager.TYPE_GROUP_VGM to VGM_EXTENSIONS,
        SettingsManager.TYPE_GROUP_GME to GME_EXTENSIONS,
        SettingsManager.TYPE_GROUP_KSS to KSS_EXTENSIONS,
        SettingsManager.TYPE_GROUP_TRACKER to TRACKER_EXTENSIONS,
        SettingsManager.TYPE_GROUP_MIDI to MIDI_EXTENSIONS,
        SettingsManager.TYPE_GROUP_MUS to MUS_EXTENSIONS,
        SettingsManager.TYPE_GROUP_RSN to RAR_ARCHIVE_EXTENSIONS,
        SettingsManager.TYPE_GROUP_PSF to PSF_EXTENSIONS
    )

    fun init(context: Context) {
        if (initialized) return
        db = VgmDatabase.getInstance(context)
        gamesDir = File(context.filesDir, "games").also { it.mkdirs() }
        appContext = context.applicationContext
        initialized = true
    }

    private fun getEnabledExtensions(): Set<String> {
        val groups = SettingsManager.getEnabledTypeGroups(appContext)
        val enabled = mutableSetOf<String>()
        groups.forEach { key ->
            EXTENSION_GROUPS[key]?.let { enabled.addAll(it) }
        }
        return enabled
    }

    private fun filterTracksByEnabledTypes(tracks: List<TrackEntity>, enabledExts: Set<String>): List<TrackEntity> {
        if (enabledExts.isEmpty()) return emptyList()
        return tracks.filter { track ->
            val path = track.filePath.lowercase()
            enabledExts.any { ext -> path.endsWith(ext) }
        }
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
                        continue
                    }
                    
                    // Handle ZIP files differently - they contain multiple tracks
                    if (fileName.endsWith(".zip", ignoreCase = true)) {
                        context.assets.open(assetPath).use { stream ->
                            importZip(stream, fileName)
                        }
                    } else if (fileName.endsWith(".rsn", ignoreCase = true) || fileName.endsWith(".rar", ignoreCase = true)) {
                        // RAR archives containing SPC/PSF files
                        context.assets.open(assetPath).use { stream ->
                            importRsn(stream, fileName)
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
                        // RSN/RAR files are RAR archives containing SPC or PSF files - extract them
                        name.endsWith(".rsn", true) || name.endsWith(".rar", true) -> {
                            try {
                                extractRsnFile(outFile, gameFolder, vgmFiles)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to extract RAR archive: ${outFile.name}", e)
                            }
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
            // Re-use existing game entry, but update system name if it's empty
            var updatedSystem = existingGame.system
            
            if (existingGame.system.isEmpty()) {
                // Try to get system name from first VGM file
                val firstVgm = sortedVgm.firstOrNull()
                if (firstVgm != null) {
                    try {
                        if (VgmEngine.open(firstVgm.absolutePath)) {
                            val tags = VgmEngine.parseTags(VgmEngine.getTags())
                            if (tags.systemEn.isNotEmpty()) {
                                updatedSystem = tags.systemEn
                            } else if (tags.systemJp.isNotEmpty()) {
                                updatedSystem = tags.systemJp
                            }
                            VgmEngine.close()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not get tags for existing game ${existingGame.name}")
                    }
                }
            }
            
            val finalGame = if (updatedSystem != existingGame.system) {
                val updatedGame = existingGame.copy(system = updatedSystem)
                db.gameDao().insertGame(updatedGame)  // REPLACE strategy will update
                updatedGame
            } else {
                existingGame
            }
            
            val tracks = db.trackDao().getTracksForGame(existingGame.id)
            val artBytes = if (artFile?.exists() == true) artFile!!.readBytes() else null
            return Game(finalGame, tracks, artBytes)
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
                VgmEngine.getTrackLengthDirect(vgmFile.absolutePath)
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
        // Detect if this is a MIDI-only zip and set system accordingly
        val hasMidiFiles = sortedVgm.any { it.extension.equals("mid", ignoreCase = true) || it.extension.equals("midi", ignoreCase = true) }
        val finalSystemName = if (systemName.isEmpty() && hasMidiFiles) "(MIDI)" else systemName
        
        val gameEntity = GameEntity(
            id = gameId,
            name = gameName,
            system = finalSystemName,
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
    
    /**
     * Create a scaled bitmap from art file that fits within max dimensions.
     * Used for displaying art in UI without modifying original files.
     */
    fun loadScaledArt(artPath: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (artPath.isEmpty()) return null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(artPath, options)
            
            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            
            // Calculate inSampleSize for efficient loading
            var inSampleSize = 1
            if (originalWidth > maxWidth || originalHeight > maxHeight) {
                val halfWidth = originalWidth / 2
                val halfHeight = originalHeight / 2
                while (halfWidth / inSampleSize >= maxWidth && halfHeight / inSampleSize >= maxHeight) {
                    inSampleSize *= 2
                }
            }
            
            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply { 
                inSampleSize = inSampleSize 
            }
            val bitmap = BitmapFactory.decodeFile(artPath, decodeOptions) ?: return null
            
            // Scale to exact dimensions while maintaining aspect ratio
            val scale = minOf(
                maxWidth.toFloat() / bitmap.width,
                maxHeight.toFloat() / bitmap.height,
                1.0f  // Don't upscale
            )
            
            if (scale < 1.0f) {
                val scaledWidth = (bitmap.width * scale).toInt()
                val scaledHeight = (bitmap.height * scale).toInt()
                return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            }
            
            return bitmap
        } catch (e: Exception) {
            return null
        }
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
        val enabledExts = getEnabledExtensions()
        val gameEntities = if (query.isBlank()) {
            db.gameDao().getAllGames()
        } else {
            db.gameDao().searchGames(query)
        }
        // Sort favorites to top, then by name
        val sorted = gameEntities.sortedWith(compareByDescending<GameEntity> { it.isFavorite }.thenBy { it.name.lowercase() })
        sorted.take(MAX_SEARCH_RESULTS).mapNotNull { gameEntity ->
            val tracks = filterTracksByEnabledTypes(db.trackDao().getTracksForGame(gameEntity.id), enabledExts)
            if (tracks.isEmpty()) return@mapNotNull null
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
        val enabledExts = getEnabledExtensions()
        filterTracksByEnabledTypes(db.trackDao().getFavoriteTracks(), enabledExts)
    }

    /** Get a specific game with its tracks */
    suspend fun getGame(gameId: Long): Game? = withContext(Dispatchers.IO) {
        val enabledExts = getEnabledExtensions()
        val gameEntity = db.gameDao().getAllGames().firstOrNull { it.id == gameId } ?: return@withContext null
        val tracks = filterTracksByEnabledTypes(db.trackDao().getTracksForGame(gameId), enabledExts)
        if (tracks.isEmpty()) return@withContext null
        val artBytes = if (gameEntity.artPath.isNotEmpty()) {
            try { File(gameEntity.artPath).readBytes() } catch (e: Exception) { null }
        } else null
        Game(gameEntity, tracks, artBytes)
    }

    /** All games (for Android Auto root browse) */
    suspend fun getAllGames(): List<Game> = withContext(Dispatchers.IO) {
        val enabledExts = getEnabledExtensions()
        db.gameDao().getAllGames().mapNotNull { gameEntity ->
            val tracks = filterTracksByEnabledTypes(db.trackDao().getTracksForGame(gameEntity.id), enabledExts)
            if (tracks.isEmpty()) return@mapNotNull null
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
    
    /** Update the art path for a game */
    suspend fun updateGameArtPath(gameId: Long, artPath: String) = withContext(Dispatchers.IO) {
        db.gameDao().updateArtPath(gameId, artPath)
    }
    
    /** Delete a game and all its tracks */
    suspend fun deleteGame(gameId: Long) = withContext(Dispatchers.IO) {
        db.trackDao().deleteTracksForGame(gameId)
        db.gameDao().deleteGameById(gameId)
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
        
        // Fallback system name based on file extension
        if (systemName.isEmpty()) {
            val ext = file.extension.lowercase()
            systemName = when {
                ext == "nsf" || ext == "nsfe" -> "Famicom (NSF)"
                ext == "gbs" -> "Game Boy (GBS)"
                ext == "hes" -> "PC Engine (HES)"
                ext == "ay" -> "ZX Spectrum (AY)"
                ext == "sap" -> "Atari ST (SAP)"
                ext == "gym" -> "Genesis (GYM)"
                ext == "spc" -> "SNES (SPC)"
                ext == "kss" || ext == "mgs" || ext == "bgm" || ext == "opx" -> "MSX (KSS)"
                ext == "vgm" || ext == "vgz" -> "VGM"
                else -> ""
            }
        }
        
        // Check for existing game
        val existingGame = db.gameDao().findByPath(gameFolder.absolutePath)
        if (existingGame != null) {
            // Re-use existing game entry, but update system name if it's empty
            var needsUpdate = false
            var updatedGame = existingGame

            if (existingGame.system.isEmpty() && systemName.isNotEmpty()) {
                updatedGame = existingGame.copy(system = systemName)
                needsUpdate = true
            }

            if (needsUpdate) {
                db.gameDao().insertGame(updatedGame)  // REPLACE strategy will update
            }

            val tracks = db.trackDao().getTracksForGame(existingGame.id)
            return Game(if (needsUpdate) updatedGame else existingGame, tracks, null)
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
    
    /**
     * Import a MUS file (Doom music) into the special "Doom MUS files" game.
     * MUS files are grouped together under a single game entry.
     */
    suspend fun importMusFile(file: File): Game? = withContext(Dispatchers.IO) {
        try {
            _importMusFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "importMusFile failed for ${file.name}", e)
            null
        }
    }
    
    private suspend fun _importMusFile(file: File, gameName: String = DOOM1_GAME_NAME, year: String = "1993"): Game? {
        // Get or create the game entry - use exact name match
        var musGame = db.gameDao().findGameByName(gameName)
        
        val musFolder = File(gamesDir, sanitizeFilename(gameName)).also { it.mkdirs() }
        
        // Copy file to MUS folder
        val destFile = File(musFolder, file.name)
        file.copyTo(destFile, overwrite = true)
        
        // Also ensure GENMIDI.lmp exists in the MUS folder (required for OPL synthesis)
        val genmidiFile = File(musFolder, "GENMIDI.lmp")
        if (!genmidiFile.exists()) {
            try {
                // Try to copy from assets
                val tempGenmidi = File(gamesDir, "GENMIDI.lmp")
                if (tempGenmidi.exists()) {
                    tempGenmidi.copyTo(genmidiFile, overwrite = false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy GENMIDI.lmp to MUS folder")
            }
        }
        
        VgmEngine.setSampleRate(44100)
        
        // Get tags from MUS file
        var trackTitle = file.nameWithoutExtension
        var authorName = "id Software"
        var systemName = "Doom (OPL3)"
        
        try {
            if (VgmEngine.open(destFile.absolutePath)) {
                val tags = VgmEngine.parseTags(VgmEngine.getTags())
                if (tags.trackEn.isNotEmpty()) trackTitle = tags.trackEn
                if (tags.authorEn.isNotEmpty()) authorName = tags.authorEn
                VgmEngine.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get tags from MUS file ${file.name}")
        }
        
        // Get duration
        val durationSamples = try {
            VgmEngine.getTrackLengthDirect(destFile.absolutePath)
        } catch (e: Exception) { 
            Log.e(TAG, "Failed to get duration for ${destFile.name}", e)
            -1L 
        }
        
        if (musGame == null) {
            // Create new game
            val gameEntity = GameEntity(
                name = gameName,
                system = "Doom (OPL3)",
                author = "id Software",
                year = year,
                folderPath = musFolder.absolutePath,
                artPath = "",
                zipSource = "mus_files"
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
            // Add to existing game
            val existingTracks = db.trackDao().getTracksForGame(musGame.id)
            val nextIndex = existingTracks.size
            
            val trackEntity = TrackEntity(
                id = 0,
                gameId = musGame.id,
                title = trackTitle,
                filePath = destFile.absolutePath,
                durationSamples = durationSamples,
                trackIndex = nextIndex,
                isFavorite = false
            )
            db.trackDao().insertTrack(trackEntity)
            
            val allTracks = db.trackDao().getTracksForGame(musGame.id)
            return Game(musGame, allTracks, null)
        }
    }
    
    /**
     * Load bundled MUS files from assets on first run.
     * @param context Android context
     * @param musFiles list of asset paths like "doom1_mus/D_E1M1.lmp"
     * @param gameName the game name to use (e.g., "Doom" or "Doom II")
     * @param year the year for the game
     */
    suspend fun loadBundledMusFilesIfNeeded(
        context: Context, 
        musFiles: List<String>,
        gameName: String = DOOM1_GAME_NAME,
        year: String = "1993"
    ) = withContext(Dispatchers.IO) {
        // Migration: Remove old "Doom MUS files" game if it exists
        val oldGameName = "Doom MUS files"
        val oldGame = db.gameDao().searchGames(oldGameName).firstOrNull()
        if (oldGame != null) {
            db.gameDao().deleteGameById(oldGame.id)
            // Also delete the folder
            val oldFolder = File(gamesDir, oldGameName)
            if (oldFolder.exists()) {
                oldFolder.deleteRecursively()
            }
        }
        
        // First, copy GENMIDI.lmp to the games root directory (needed for OPL synthesis)
        try {
            val genmidiAsset = "GENMIDI.lmp"
            val tempGenmidi = File(context.cacheDir, genmidiAsset)
            context.assets.open(genmidiAsset).use { input ->
                tempGenmidi.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Copy to games directory so it can be found by the native code
            val gamesGenmidi = File(gamesDir, genmidiAsset)
            tempGenmidi.copyTo(gamesGenmidi, overwrite = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy GENMIDI.lmp", e)
        }
        
        // Get existing tracks to avoid duplicates - use exact name match
        val existingGame = db.gameDao().findGameByName(gameName)
        val existingTrackNames: Set<String> = existingGame?.let { game ->
            db.trackDao().getTracksForGame(game.id).map { it.title }.toSet()
        } ?: emptySet()
        
        var importedCount = 0
        for (assetPath in musFiles) {
            try {
                val fileName = assetPath.substringAfterLast('/')
                
                // Skip if track already exists
                if (fileName in existingTrackNames) {
                    continue
                }
                
                val tempFile = File(context.cacheDir, fileName)
                context.assets.open(assetPath).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                _importMusFile(tempFile, gameName, year)
                tempFile.delete()
                importedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bundled MUS file $assetPath", e)
            }
        }
        if (importedCount > 0) {
        }
    }
    
    /**
     * Import a MIDI file into the special "MIDI files" game.
     * MIDI files are grouped together under a single game entry.
     */
    suspend fun importMidiFile(file: File): Game? = withContext(Dispatchers.IO) {
        try {
            _importMidiFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "importMidiFile failed for ${file.name}", e)
            null
        }
    }
    
    private suspend fun _importMidiFile(file: File): Game? {
        // Get or create the "MIDI files" game entry
        var midiGame = db.gameDao().searchGames(MIDI_GAME_NAME).firstOrNull()
        
        val midiFolder = File(gamesDir, sanitizeFilename(MIDI_GAME_NAME)).also { it.mkdirs() }
        
        // Copy file to MIDI folder
        val destFile = File(midiFolder, file.name)
        file.copyTo(destFile, overwrite = true)
        
        VgmEngine.setSampleRate(44100)
        
        // Get title from MIDI file
        var trackTitle = file.nameWithoutExtension
        
        try {
            if (VgmEngine.open(destFile.absolutePath)) {
                val tags = VgmEngine.parseTags(VgmEngine.getTags())
                if (tags.trackEn.isNotEmpty()) trackTitle = tags.trackEn
                VgmEngine.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get tags from MIDI file ${file.name}")
        }
        
        // Get duration (MIDI files have variable length)
        val durationSamples = try {
            VgmEngine.getTrackLengthDirect(destFile.absolutePath)
        } catch (e: Exception) { 
            Log.e(TAG, "Failed to get duration for ${destFile.name}", e)
            -1L 
        }
        
        if (midiGame == null) {
            // Create new "MIDI files" game
            val gameEntity = GameEntity(
                name = MIDI_GAME_NAME,
                system = "(MIDI)",
                author = "",
                year = "",
                folderPath = midiFolder.absolutePath,
                artPath = "",
                zipSource = "midi_files"
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
            // Add to existing "MIDI files" game
            val existingTracks = db.trackDao().getTracksForGame(midiGame.id)
            val nextIndex = existingTracks.size
            
            val trackEntity = TrackEntity(
                id = 0,
                gameId = midiGame.id,
                title = trackTitle,
                filePath = destFile.absolutePath,
                durationSamples = durationSamples,
                trackIndex = nextIndex,
                isFavorite = false
            )
            db.trackDao().insertTrack(trackEntity)
            
            val allTracks = db.trackDao().getTracksForGame(midiGame.id)
            return Game(midiGame, allTracks, null)
        }
    }
    
    /**
     * Load bundled MIDI files from assets on first run.
     * midiFiles: list of asset paths like "doom1/E1M1.mid"
     */
    suspend fun loadBundledMidiFilesIfNeeded(context: Context, midiFiles: List<String>) =
        withContext(Dispatchers.IO) {
            // Check if "MIDI files" game already exists
            if (db.gameDao().searchGames(MIDI_GAME_NAME).isNotEmpty()) {
                return@withContext
            }
            
            for (assetPath in midiFiles) {
                try {
                    val fileName = assetPath.substringAfterLast('/')
                    val tempFile = File(context.cacheDir, fileName)
                    context.assets.open(assetPath).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    importMidiFile(tempFile)
                    tempFile.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load bundled MIDI file $assetPath", e)
                }
            }
        }
    
    /**
     * Extract SPC files from an RSN (RAR) archive.
     * RSN files are RAR archives containing SPC files for SNES music.
     */
    private fun extractRsnFile(rsnFile: File, gameFolder: File, vgmFiles: MutableList<File>) {
        try {
            val archive = Archive(rsnFile)
            var header: FileHeader? = archive.nextFileHeader()
            
            while (header != null) {
                if (!header.isDirectory) {
                    val name = header.fileName.substringAfterLast('/')
                    
                    // Extract SPC files (for RSN) and PSF files (for PSF archives)
                    if (name.endsWith(".spc", ignoreCase = true) || 
                        name.endsWith(".psf", ignoreCase = true) ||
                        name.endsWith(".psf1", ignoreCase = true) ||
                        name.endsWith(".psf2", ignoreCase = true) ||
                        name.endsWith(".minipsf", ignoreCase = true) ||
                        name.endsWith(".minipsf1", ignoreCase = true) ||
                        name.endsWith(".minipsf2", ignoreCase = true)) {
                        val outFile = File(gameFolder, sanitizeFilename(name))
                        outFile.outputStream().use { out ->
                            archive.extractFile(header, out)
                        }
                        vgmFiles.add(outFile)
                    }
                }
                header = archive.nextFileHeader()
            }
            
            archive.close()
            
            // Delete the RSN/RAR file after extraction to save space
            rsnFile.delete()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract RSN file: ${rsnFile.name}", e)
        }
    }
    
    /**
     * Import an RSN file (RAR archive containing SPC files) directly.
     * This is used when downloading RSN files from the SNES Music Archive.
     */
    suspend fun importRsn(inputStream: InputStream, rsnName: String): Game? =
        withContext(Dispatchers.IO) {
            try {
                _importRsn(inputStream, rsnName)
            } catch (e: Exception) {
                Log.e(TAG, "importRsn failed for $rsnName", e)
                null
            }
        }
    
    private suspend fun _importRsn(inputStream: InputStream, rsnName: String): Game? {
        
        // Use RSN stem as folder name
        val folderName = rsnName.removeSuffix(".rsn").removeSuffix(".RSN")
        val gameFolder = File(gamesDir, sanitizeFilename(folderName)).also { it.mkdirs() }
        
        // Save the RSN file temporarily
        val tempRsnFile = File(gameFolder, "temp.rsn")
        inputStream.use { input ->
            tempRsnFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        // Extract SPC files from the RSN using Junrar.extract()
        val vgmFiles = mutableListOf<File>()
        var gameName = folderName
        var authorName = ""
        var yearStr = ""
        var systemName = "" // Will be detected from file type
        
        try {
            // Use the simpler Junrar.extract method
            com.github.junrar.Junrar.extract(tempRsnFile, gameFolder)
            
            // Now scan the extracted files
            gameFolder.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                
                when {
                    name.endsWith(".spc") || 
                    name.endsWith(".psf") ||
                    name.endsWith(".psf1") ||
                    name.endsWith(".psf2") ||
                    name.endsWith(".minipsf") ||
                    name.endsWith(".minipsf1") ||
                    name.endsWith(".minipsf2") -> {
                        vgmFiles.add(file)
                    }
                    name == "info.txt" -> {
                        // Parse info.txt for game details
                        try {
                            val lines = file.readText().lines()
                            lines.forEach { line ->
                                when {
                                    line.startsWith("Game:", ignoreCase = true) -> 
                                        gameName = line.substringAfter(":").trim()
                                    line.startsWith("Artist:", ignoreCase = true) || 
                                        line.startsWith("Composer:", ignoreCase = true) -> 
                                        authorName = line.substringAfter(":").trim()
                                    line.startsWith("Year:", ignoreCase = true) || 
                                        line.startsWith("Date:", ignoreCase = true) -> 
                                        yearStr = line.substringAfter(":").trim()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse info.txt", e)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract RSN file: $rsnName", e)
        }
        
        // Delete the temp RSN file
        tempRsnFile.delete()
        
        if (vgmFiles.isEmpty()) {
            Log.w(TAG, "No audio files found in RSN/RAR: $rsnName")
            return null
        }
        
        // Auto-detect system based on file extensions
        val hasPsfFiles = vgmFiles.any { it.extension.lowercase() in listOf("psf", "psf1", "psf2", "minipsf", "minipsf1", "minipsf2") }
        if (systemName.isEmpty()) {
            systemName = if (hasPsfFiles) "PlayStation" else "Super Nintendo"
        }
        
        // Sort files by name
        val sortedVgm = vgmFiles.sortedBy { it.name }
        
        // Get tags from first track (may override info.txt values)
        VgmEngine.setSampleRate(44100)
        val trackEntities = mutableListOf<TrackEntity>()
        
        // Insert game into DB first (need ID for tracks)
        val existingGame = db.gameDao().findByPath(gameFolder.absolutePath)
        if (existingGame != null) {
            // Re-use existing game entry
            val tracks = db.trackDao().getTracksForGame(existingGame.id)
            return Game(existingGame, tracks, null)
        }
        
        val tempGameEntity = GameEntity(
            name = gameName, system = systemName, author = authorName, year = yearStr,
            folderPath = gameFolder.absolutePath,
            artPath = "",
            zipSource = rsnName
        )
        val gameId = db.gameDao().insertGame(tempGameEntity)
        
        // Scan tracks for duration + tags
        sortedVgm.forEachIndexed { idx, vgmFile ->
            val durationSamples = try {
                VgmEngine.getTrackLengthDirect(vgmFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get duration for ${vgmFile.name}", e)
                0L
            }
            
            // Get tags from first track only (may override info.txt values)
            if (idx == 0) {
                try {
                    if (VgmEngine.open(vgmFile.absolutePath)) {
                        val tags = VgmEngine.parseTags(VgmEngine.getTags())
                        // Use English game name, fallback to Japanese, fallback to info.txt/folder name
                        if (tags.gameEn.isNotEmpty()) gameName = tags.gameEn
                        else if (tags.gameJp.isNotEmpty()) gameName = tags.gameJp
                        // Use English system name, fallback to Japanese
                        if (tags.systemEn.isNotEmpty()) systemName = tags.systemEn
                        else if (tags.systemJp.isNotEmpty()) systemName = tags.systemJp
                        // Use English author name, fallback to Japanese, fallback to info.txt
                        if (tags.authorEn.isNotEmpty()) authorName = tags.authorEn
                        else if (tags.authorJp.isNotEmpty()) authorName = tags.authorJp
                        // Use date from tags if available
                        if (tags.date.isNotEmpty()) yearStr = tags.date
                        
                        VgmEngine.close()
                    }
                } catch (e: Exception) { Log.w(TAG, "Could not get tags from ${vgmFile.name}") }
            }
            
            val trackTitle = vgmFile.nameWithoutExtension
            
            val trackEntity = TrackEntity(
                gameId = gameId,
                trackIndex = idx,
                filePath = vgmFile.absolutePath,
                title = trackTitle,
                durationSamples = durationSamples
            )
            trackEntities.add(trackEntity)
            db.trackDao().insertTrack(trackEntity)
        }
        
        // Update game with final info
        val finalGameEntity = GameEntity(
            id = gameId,
            name = gameName, system = systemName, author = authorName, year = yearStr,
            folderPath = gameFolder.absolutePath,
            artPath = "",
            zipSource = rsnName,
            soundChips = "SPC"
        )
        db.gameDao().updateGame(finalGameEntity)
        
        return Game(finalGameEntity, trackEntities, null)
    }

    /**
     * Export all games to a ZIP file with a JSON manifest.
     * Returns the output file on success, null on failure.
     */
    suspend fun exportAllToZip(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            
            val allGames = db.gameDao().getAllGames()
            if (allGames.isEmpty()) {
                Log.w(TAG, "No games to export")
                return@withContext false
            }


            val addedEntries = mutableSetOf<String>()
            
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                // Create manifest
                val manifest = JSONObject().apply {
                    put("version", 1)
                    put("exportedAt", System.currentTimeMillis())
                    put("gameCount", allGames.size)
                }

                val gamesArray = JSONArray()

                // Export each game
                for (gameEntity in allGames) {
                    val gameFolder = File(gameEntity.folderPath)
                    
                    if (!gameFolder.exists()) {
                        Log.w(TAG, "Game folder does not exist, skipping: ${gameEntity.name}")
                        continue
                    }

                    val tracks = db.trackDao().getTracksForGame(gameEntity.id)
                    
                    val gameJson = JSONObject().apply {
                        put("name", gameEntity.name)
                        put("system", gameEntity.system)
                        put("author", gameEntity.author)
                        put("year", gameEntity.year)
                        put("soundChips", gameEntity.soundChips)
                        put("folderName", gameFolder.name)

                        // Export artwork if exists
                        if (gameEntity.artPath.isNotEmpty()) {
                            val artFile = File(gameEntity.artPath)
                            if (artFile.exists()) {
                                put("artFileName", artFile.name)
                                val artEntry = "art/${artFile.name}"
                                if (!addedEntries.contains(artEntry)) {
                                    addFileToZip(zos, artFile, artEntry)
                                    addedEntries.add(artEntry)
                                }
                            }
                        }

                        // Export tracks
                        val tracksArray = JSONArray()
                        for (track in tracks) {
                            val trackFile = File(track.filePath)
                            
                            if (trackFile.exists()) {
                                val relativePath = "games/${gameFolder.name}/${trackFile.name}"
                                if (!addedEntries.contains(relativePath)) {
                                    addFileToZip(zos, trackFile, relativePath)
                                    addedEntries.add(relativePath)
                                }

                                val trackJson = JSONObject().apply {
                                    put("title", track.title)
                                    put("fileName", trackFile.name)
                                    put("trackIndex", track.trackIndex)
                                    put("durationSamples", track.durationSamples)
                                    put("isFavorite", track.isFavorite)
                                    put("subTrackIndex", track.subTrackIndex)
                                }
                                tracksArray.put(trackJson)
                            }
                        }
                        put("tracks", tracksArray)
                    }
                    gamesArray.put(gameJson)
                }

                manifest.put("games", gamesArray)

                // Add manifest to ZIP
                val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
                val manifestEntry = ZipEntry("manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(manifestBytes)
                zos.closeEntry()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            outputFile.delete()
            // Rethrow the exception to provide more details to UI
            throw e
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /**
     * Import games from an exported ZIP file.
     * Returns the number of games imported on success, -1 on failure.
     */
    suspend fun importFromZip(inputFile: File): Int = withContext(Dispatchers.IO) {
        try {
            var gameCount = 0

            ZipInputStream(BufferedInputStream(FileInputStream(inputFile))).use { zis ->
                // First pass: extract all files to temp location
                val tempExtractDir = File(appContext.cacheDir, "import_${System.currentTimeMillis()}")
                tempExtractDir.mkdirs()

                var entry = zis.nextEntry
                val extractedFiles = mutableMapOf<String, File>() // entryName -> file

                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(tempExtractDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedFiles[entry.name] = outFile
                    }
                    entry = zis.nextEntry
                }

                // Read manifest
                val manifestFile = extractedFiles["manifest.json"]
                if (manifestFile == null) {
                    Log.e(TAG, "No manifest.json found in import file")
                    tempExtractDir.deleteRecursively()
                    return@withContext -1
                }

                val manifest = JSONObject(manifestFile.readText())
                val gamesArray = manifest.getJSONArray("games")

                // Import each game
                for (i in 0 until gamesArray.length()) {
                    val gameJson = gamesArray.getJSONObject(i)
                    val gameName = gameJson.getString("name")
                    val folderName = gameJson.getString("folderName")

                    // Create game folder in library
                    val gameFolder = File(gamesDir, sanitizeFilename(folderName)).also { it.mkdirs() }

                    // Copy extracted files to game folder
                    val gameFilesDir = File(tempExtractDir, "games/$folderName")
                    if (gameFilesDir.exists()) {
                        gameFilesDir.listFiles()?.forEach { file ->
                            if (!file.isDirectory) {
                                val destFile = File(gameFolder, file.name)
                                file.copyTo(destFile, overwrite = true)
                            }
                        }
                    }

                    // Copy artwork if exists
                    val artFileName = gameJson.optString("artFileName", "")
                    var artPath = ""
                    if (artFileName.isNotEmpty()) {
                        val artSource = File(tempExtractDir, "art/$artFileName")
                        if (artSource.exists()) {
                            val destArt = File(gameFolder, artFileName)
                            artSource.copyTo(destArt, overwrite = true)
                            artPath = destArt.absolutePath
                        }
                    }

                    // Import tracks to database
                    val tracksArray = gameJson.getJSONArray("tracks")
                    val trackEntities = mutableListOf<TrackEntity>()

                    // Check if game already exists (by exact name or path)
                    val existingByName = db.gameDao().findGameByName(gameName)
                    if (existingByName != null) {
                        continue // Skip to next game
                    }
                    
                    val existingByPath = db.gameDao().findByPath(gameFolder.absolutePath)
                    if (existingByPath != null) {
                        continue // Skip to next game
                    }

                    // Create new game entry
                    val gameEntity = GameEntity(
                        name = gameName,
                        system = gameJson.optString("system", ""),
                        author = gameJson.optString("author", ""),
                        year = gameJson.optString("year", ""),
                        folderPath = gameFolder.absolutePath,
                        artPath = artPath,
                        zipSource = inputFile.name,
                        soundChips = gameJson.optString("soundChips", "")
                    )
                    val gameId = db.gameDao().insertGame(gameEntity)

                    // Insert tracks
                    for (j in 0 until tracksArray.length()) {
                        val trackJson = tracksArray.getJSONObject(j)
                        val fileName = trackJson.getString("fileName")
                        val trackFile = File(gameFolder, fileName)

                        if (trackFile.exists()) {
                            val trackEntity = TrackEntity(
                                gameId = gameId,
                                title = trackJson.getString("title"),
                                filePath = trackFile.absolutePath,
                                trackIndex = trackJson.getInt("trackIndex"),
                                durationSamples = trackJson.optLong("durationSamples", 0),
                                isFavorite = trackJson.optBoolean("isFavorite", false),
                                subTrackIndex = trackJson.optInt("subTrackIndex", -1)
                            )
                            trackEntities.add(trackEntity)
                            db.trackDao().insertTrack(trackEntity)
                        }
                    }

                    gameCount++
                }

                // Cleanup temp directory
                tempExtractDir.deleteRecursively()
            }

            gameCount
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            -1
        }
    }
}

private data class ExportGameInfo(
    val name: String,
    val system: String,
    val author: String,
    val year: String,
    val soundChips: String,
    val folderName: String,
    val artFileName: String,
    val tracks: List<ExportTrackInfo>
)

private data class ExportTrackInfo(
    val title: String,
    val fileName: String,
    val trackIndex: Int,
    val durationSamples: Long,
    val isFavorite: Boolean
)
