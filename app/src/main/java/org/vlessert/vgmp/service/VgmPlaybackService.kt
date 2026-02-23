package org.vlessert.vgmp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.VgmServiceBinder
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.engine.VgmTags
import org.vlessert.vgmp.library.Game
import org.vlessert.vgmp.library.GameLibrary
import org.vlessert.vgmp.library.TrackEntity
import org.vlessert.vgmp.settings.SettingsManager
import java.io.File

class VgmPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        const val NOTIF_CHANNEL_ID = "vgmp_playback"
        const val NOTIF_ID = 1
        const val SAMPLE_RATE = 44100
        const val BUFFER_FRAMES = 4096
        const val ACTION_PLAY   = "org.vlessert.vgmp.ACTION_PLAY"
        const val ACTION_PAUSE  = "org.vlessert.vgmp.ACTION_PAUSE"
        const val ACTION_NEXT   = "org.vlessert.vgmp.ACTION_NEXT"
        const val ACTION_PREV   = "org.vlessert.vgmp.ACTION_PREV"
        const val ACTION_STOP   = "org.vlessert.vgmp.ACTION_STOP"
        const val MEDIA_ID_ROOT = "root"
        private const val TAG = "VgmPlaybackService"
        private const val FADE_MS = 2000L
    }

    enum class ShuffleMode { OFF, GAME, ALL }
    enum class LoopMode { OFF, TRACK, GAME }

    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Playback state
    private var allGames: List<Game> = emptyList()
    private var currentGameIdx: Int = -1
    private var currentTrackIdx: Int = -1
    private var isPlaying = false
    private var isPaused  = false
    private var shouldPlayAfterFocusGain = false
    private var shuffleMode = ShuffleMode.OFF
    private var loopMode = LoopMode.OFF
    private var currentTags = VgmTags()
    private var trackDurationMs = 0L

    // For fade out
    private var fadeStartTimeMs = 0L
    private var isFadingOut = false
    private var currentVolume = 1.0f
    
    // Endless loop mode
    private var endlessLoopMode = false

    // Render thread
    private val _spectrum = MutableStateFlow(FloatArray(512))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    private var renderJob: Job? = null
    private val renderBuffer = ShortArray(BUFFER_FRAMES * 2)  // interleaved stereo

    private val _playbackState = MutableStateFlow<PlaybackInfo>(PlaybackInfo())
    val playbackInfo = _playbackState.asStateFlow()

    // Library ready state - emits when downloads complete
    private val _libraryReady = MutableStateFlow(false)
    val libraryReady: StateFlow<Boolean> = _libraryReady.asStateFlow()

    data class PlaybackInfo(
        val playing: Boolean = false,
        val paused: Boolean = false,
        val gameIdx: Int = -1,
        val trackIdx: Int = -1,
        val track: TrackEntity? = null,
        val durationMs: Long = 0L,
        val endlessLoop: Boolean = false
    )

    // Position tracking
    private var playbackStartTimeMs = 0L
    private var pausedPositionMs    = 0L

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        setupMediaSession()
        
        // Fix for "Context.startForegroundService() did not then call Service.startForeground()"
        // We must call startForeground immediately upon creation when started as a foreground service.
        startForeground(NOTIF_ID, buildNotification(false))

        VgmEngine.nSetSampleRate(SAMPLE_RATE)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Load bundled assets + populate library
        serviceScope.launch {
            VgmEngine.setSampleRate(SAMPLE_RATE) // Use thread-safe version
            extractRoms()
            loadBundledAssets()
            allGames = GameLibrary.getAllGames()
            _libraryReady.value = true
        }
    }

    private suspend fun loadBundledAssets() = withContext(Dispatchers.IO) {
        // Auto-download SD Snatcher, Quarth MSX 2, and Bombaman if not present
        // This runs regardless of whether database is empty (handles stream installs)
        val autoDownloadUrls = listOf(
            "https://vgmrips.net/files/Computers/MSX/SD_Snatcher_(MSX2).zip" to "SD Snatcher",
            "https://vgmrips.net/files/Computers/MSX/Quarth_(MSX2).zip" to "Quarth",
            "https://vgmrips.net/files/Computers/MSX/Bombaman_Extra_Ammo_(MSX2).zip" to "Bombaman"
        )
        for ((url, gameName) in autoDownloadUrls) {
            // Check if game already exists
            if (!GameLibrary.gameExists(gameName)) {
                try {
                    Log.d(TAG, "Auto-downloading $gameName")
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { input ->
                            GameLibrary.importZip(input, "$gameName.zip")
                        }
                        Log.d(TAG, "Auto-downloaded $gameName successfully")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-download $gameName", e)
                }
            } else {
                Log.d(TAG, "$gameName already exists, skipping auto-download")
            }
        }
        allGames = GameLibrary.getAllGames()
    }

    private suspend fun extractRoms() = withContext(Dispatchers.IO) {
        val romsDir = File(filesDir, "roms").also { it.mkdirs() }
        val romFileName = "yrw801.rom"
        val destFile = File(romsDir, romFileName)

        if (!destFile.exists()) {
            try {
                assets.open(romFileName).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Extracted $romFileName to ${destFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $romFileName", e)
            }
        }
        VgmEngine.setRomPath(romsDir.absolutePath)
    }

    private fun setupMediaSession() {
        val activityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "VgmPlayback").apply {
            setSessionActivity(pendingIntent)
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { resumeOrPlay() }
        override fun onPause() { pausePlayback() }
        override fun onStop() { stopPlayback() }
        override fun onSkipToNext() { nextTrack() }
        override fun onSkipToPrevious() { previousTrack() }
        override fun onSeekTo(pos: Long) { seekTo(pos) }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return
            // Format: "gameIdx/trackIdx"
            val parts = mediaId.split("/")
            if (parts.size == 2) {
                val gi = parts[0].toIntOrNull() ?: return
                val ti = parts[1].toIntOrNull() ?: return
                serviceScope.launch { loadAndPlay(gi, ti) }
            }
        }
        override fun onSetRepeatMode(repeatMode: Int) {
            loopMode = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> LoopMode.TRACK
                PlaybackStateCompat.REPEAT_MODE_ALL -> LoopMode.GAME
                else -> LoopMode.OFF
            }
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            this@VgmPlaybackService.shuffleMode = when(shuffleMode) {
                PlaybackStateCompat.SHUFFLE_MODE_ALL -> ShuffleMode.ALL
                PlaybackStateCompat.SHUFFLE_MODE_GROUP -> ShuffleMode.GAME
                else -> ShuffleMode.OFF
            }
        }
    }

    // ------- Audio focus setup -------
    
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pendingPlayback: Pair<Game, TrackEntity>? = null // game, track for delayed playback
    private var hasAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioTrack?.setVolume(1.0f)
                if (shouldPlayAfterFocusGain) resumeOrPlay()
                // Handle delayed focus gain for pending playback
                pendingPlayback?.let { (game, track) ->
                    Log.d(TAG, "Audio focus gained after delay, starting playback")
                    pendingPlayback = null
                    serviceScope.launch { startTrackWithFocus(game, track) }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                shouldPlayAfterFocusGain = false
                pendingPlayback = null
                pausePlayback()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                shouldPlayAfterFocusGain = isPlaying && !isPaused
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioTrack?.setVolume(0.2f)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || 
                   result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    // ------- Audio setup -------

    private fun createAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, BUFFER_FRAMES * 2 * 2) // frames * channels * bytes/sample
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ------- Playback control -------

    suspend fun loadAndPlay(gameIdx: Int, trackIdx: Int) {
        if (gameIdx < 0 || gameIdx >= allGames.size) return
        val game = allGames[gameIdx]
        if (trackIdx < 0 || trackIdx >= game.tracks.size) return
        val track = game.tracks[trackIdx]
        currentGameIdx  = gameIdx
        currentTrackIdx = trackIdx
        startTrack(game, track)
    }

    private suspend fun startTrack(game: Game, track: TrackEntity) {
        stopRenderJob()
        
        // Request audio focus - handle delayed focus for Android Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                Log.e(TAG, "Audio focus request failed")
                return
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                Log.d(TAG, "Audio focus delayed, storing pending playback")
                pendingPlayback = Pair(game, track)
                return
            }
            hasAudioFocus = true
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.e(TAG, "Failed to get audio focus")
                return
            }
            hasAudioFocus = true
        }
        
        startTrackWithFocus(game, track)
    }

    private suspend fun startTrackWithFocus(game: Game, track: TrackEntity) {
        val opened = VgmEngine.open(track.filePath)
        if (!opened) {
            Log.e(TAG, "Failed to open ${track.filePath}")
            return
        }
        
        // For multi-track files (NSF, GBS, etc.), switch to the correct sub-track
        if (track.subTrackIndex >= 0) {
            val switched = VgmEngine.setTrack(track.subTrackIndex)
            if (!switched) {
                Log.e(TAG, "Failed to set sub-track ${track.subTrackIndex}")
            }
        }

        // Reset endless loop mode when starting a new track
        if (endlessLoopMode) {
            endlessLoopMode = false
            VgmEngine.setEndlessLoop(false)
        }

        // Parse tags
        currentTags = VgmEngine.parseTags(VgmEngine.getTags())
        
        // Get live duration from engine and compare with stored value
        val liveDurationSamples = VgmEngine.getTotalSamples()
        val storedDurationSamples = track.durationSamples
        trackDurationMs = if (liveDurationSamples > 0)
            liveDurationSamples * 1000L / SAMPLE_RATE else 0L
        
        Log.d(TAG, "Track duration: stored=$storedDurationSamples, live=$liveDurationSamples, trackDurationMs=$trackDurationMs, filePath=${track.filePath}")

        // Album art - use game art or fallback to app logo
        val artBitmap: Bitmap? = if (game.artPath.isNotEmpty() && File(game.artPath).exists()) {
            try { BitmapFactory.decodeFile(game.artPath) } catch (e: Exception) { getFallbackArt() }
        } else {
            getFallbackArt()
        }

        // Update MediaSession metadata (→ AVRCP 1.6)
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTags.displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentTags.displayAuthor.ifEmpty { game.name })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentTags.displayGame.ifEmpty { game.name })
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentTags.displaySystem)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, trackDurationMs)
            .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (currentTrackIdx + 1).toLong())
        if (artBitmap != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
        }
        mediaSession.setMetadata(metaBuilder.build())

        // Start audio track and render loop
        isPlaying = true
        isPaused  = false
        playbackStartTimeMs = SystemClock.elapsedRealtime()
        pausedPositionMs    = 0L

        audioTrack?.release()
        audioTrack = createAudioTrack().also { it.play() }

        startRenderJob()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForeground(NOTIF_ID, buildNotification(true))
        _playbackState.value = PlaybackInfo(true, false, currentGameIdx, currentTrackIdx, track, trackDurationMs)
    }

    // Position update tracking
    private var lastPositionUpdateMs = 0L
    private val POSITION_UPDATE_INTERVAL_MS = 500L

    private fun startRenderJob() {
        renderJob = serviceScope.launch(Dispatchers.IO) {
            try {
                while (isActive && isPlaying) {
                    if (isPaused) {
                        delay(50)
                        continue
                    }
                    val framesWritten = VgmEngine.fillBuffer(renderBuffer, BUFFER_FRAMES)
                    if (framesWritten > 0) {
                        applyVolumeAndFade(renderBuffer, framesWritten)
                        audioTrack?.write(renderBuffer, 0, framesWritten * 2)

                        // Update spectrum for UI
                        val spectrumBuffer = FloatArray(512)
                        VgmEngine.getSpectrum(spectrumBuffer)
                        _spectrum.emit(spectrumBuffer)
                    }
                    
                    // Periodically update playback state for position tracking (must be BEFORE endless loop check)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPositionUpdateMs >= POSITION_UPDATE_INTERVAL_MS) {
                        lastPositionUpdateMs = now
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }
                    
                    // Skip fade out and track end detection in endless loop mode
                    if (endlessLoopMode) continue
                    
                    // Trigger fade out before actual end if we know the duration
                    val pos = currentPositionMs()
                    if (trackDurationMs > 0 && pos >= trackDurationMs - FADE_MS && !isFadingOut) {
                        startFadeOut()
                    }

                    // Check if track ended
                    if (VgmEngine.isEnded() || (isFadingOut && SystemClock.elapsedRealtime() >= fadeStartTimeMs + FADE_MS)) {
                        onTrackEnded()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Render loop error", e)
            }
        }
    }

    private fun startFadeOut() {
        if (isFadingOut) return
        isFadingOut = true
        fadeStartTimeMs = SystemClock.elapsedRealtime()
    }

    private fun applyVolumeAndFade(buffer: ShortArray, frames: Int) {
        if (!isFadingOut) return
        
        val elapsed = SystemClock.elapsedRealtime() - fadeStartTimeMs
        val fadeFactor = (1.0f - (elapsed.toFloat() / FADE_MS)).coerceIn(0f, 100f)
        
        for (i in 0 until (frames * 2)) {
            buffer[i] = (buffer[i] * fadeFactor).toInt().toShort()
        }
    }

    private fun stopRenderJob() {
        renderJob?.cancel()
        renderJob = null
        isFadingOut = false
        audioTrack?.pause()
        audioTrack?.flush()
    }

    private suspend fun onTrackEnded() {
        when (loopMode) {
            LoopMode.TRACK -> {
                // Restart same track
                val game = allGames.getOrNull(currentGameIdx) ?: return
                val track = game.tracks.getOrNull(currentTrackIdx) ?: return
                startTrack(game, track)
            }
            LoopMode.GAME -> {
                // Next track in same game, loop to start if at end
                val game = allGames.getOrNull(currentGameIdx) ?: return
                val nextT = if (currentTrackIdx + 1 < game.tracks.size) currentTrackIdx + 1 else 0
                loadAndPlay(currentGameIdx, nextT)
            }
            LoopMode.OFF -> {
                nextTrack()
            }
        }
    }

    private fun resumeOrPlay() {
        if (!isPlaying) {
            // Start first track if nothing is loaded
            if (currentGameIdx < 0 && allGames.isNotEmpty()) {
                serviceScope.launch { loadAndPlay(0, 0) }
            }
            return
        }
        if (isPaused) {
            isPaused = false
            playbackStartTimeMs = SystemClock.elapsedRealtime() - pausedPositionMs
            audioTrack?.play()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification(true)
            _playbackState.value = _playbackState.value.copy(paused = false, playing = true)
        }
    }

    private fun pausePlayback() {
        if (!isPlaying || isPaused) return
        isPaused = true
        pausedPositionMs = SystemClock.elapsedRealtime() - playbackStartTimeMs
        audioTrack?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification(false)
        _playbackState.value = _playbackState.value.copy(paused = true, playing = true)
    }

    private fun stopPlayback() {
        isPlaying = false
        isPaused  = false
        stopRenderJob()
        serviceScope.launch {
            VgmEngine.stop()
            VgmEngine.close()
        }
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        abandonAudioFocus()
        _playbackState.value = PlaybackInfo()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun nextTrack() {
        if (!isFadingOut) {
            // Manual skip - maybe smaller fade or immediate?
            // User requested fade out to next track.
            serviceScope.launch {
                startFadeOut()
                delay(500) // Short fade for manual skip
                performNextTrack()
            }
        } else {
            serviceScope.launch { performNextTrack() }
        }
    }

    private suspend fun performNextTrack() {
        allGames = GameLibrary.getAllGames()
        if (allGames.isEmpty()) return
        
        val favoritesOnly = SettingsManager.isFavoritesOnlyMode(applicationContext)
        
        val (nextG, nextT) = when (shuffleMode) {
            ShuffleMode.ALL -> {
                if (favoritesOnly) {
                    // Favorites only: only pick from favorite games and tracks
                    val favoriteGames = allGames.filter { it.entity.isFavorite }
                    if (favoriteGames.isEmpty()) {
                        // No favorite games, fall back to all games with favorite tracks
                        val gamesWithFavTracks = allGames.filter { game -> game.tracks.any { it.isFavorite } }
                        if (gamesWithFavTracks.isEmpty()) return // No favorites at all
                        val game = gamesWithFavTracks.random()
                        val favTracks = game.tracks.filter { it.isFavorite }
                        val ti = game.tracks.indexOf(favTracks.random())
                        allGames.indexOf(game) to ti
                    } else {
                        val game = favoriteGames.random()
                        val favTracks = game.tracks.filter { it.isFavorite }
                        val tracks = if (favTracks.isNotEmpty()) favTracks else game.tracks
                        val ti = game.tracks.indexOf(tracks.random())
                        allGames.indexOf(game) to ti
                    }
                } else {
                    // Weighted random: favorite games have 3x weight
                    val weightedGames = allGames.flatMap { game ->
                        val weight = if (game.entity.isFavorite) 3 else 1
                        List(weight) { allGames.indexOf(game) }
                    }
                    val gi = weightedGames.random()
                    // Weighted random: favorite tracks have 3x weight
                    val game = allGames[gi]
                    val weightedTracks = game.tracks.flatMap { track ->
                        val weight = if (track.isFavorite) 3 else 1
                        List(weight) { game.tracks.indexOf(track) }
                    }
                    val ti = weightedTracks.random()
                    gi to ti
                }
            }
            ShuffleMode.GAME -> {
                val game = allGames.getOrNull(currentGameIdx) ?: allGames[0]
                val gi = allGames.indexOf(game)
                if (favoritesOnly) {
                    // Favorites only: only pick favorite tracks from this game
                    val favTracks = game.tracks.filter { it.isFavorite }
                    if (favTracks.isEmpty()) {
                        // No favorite tracks in this game, move to next game with favorites
                        val gamesWithFavTracks = allGames.filter { g -> g.tracks.any { it.isFavorite } }
                        if (gamesWithFavTracks.isEmpty()) return
                        val nextGame = gamesWithFavTracks.random()
                        val nextFavTracks = nextGame.tracks.filter { it.isFavorite }
                        val ti = nextGame.tracks.indexOf(nextFavTracks.random())
                        allGames.indexOf(nextGame) to ti
                    } else {
                        val ti = game.tracks.indexOf(favTracks.random())
                        gi to ti
                    }
                } else {
                    // Weighted random: favorite tracks have 3x weight
                    val weightedTracks = game.tracks.flatMap { track ->
                        val weight = if (track.isFavorite) 3 else 1
                        List(weight) { game.tracks.indexOf(track) }
                    }
                    val ti = weightedTracks.random()
                    gi to ti
                }
            }
            ShuffleMode.OFF -> {
                if (favoritesOnly) {
                    // Find next favorite track in sequence
                    findNextFavoriteTrack()
                } else {
                    val game = allGames.getOrNull(currentGameIdx)
                    if (game != null && currentTrackIdx + 1 < game.tracks.size) {
                        currentGameIdx to currentTrackIdx + 1
                    } else if (loopMode == LoopMode.GAME && game != null) {
                        // Loop to start of this game
                        currentGameIdx to 0
                    } else {
                        // Move to next game
                        val nextG2 = (currentGameIdx + 1) % allGames.size
                        nextG2 to 0
                    }
                }
            }
        }
        loadAndPlay(nextG, nextT)
    }
    
    private fun findNextFavoriteTrack(): Pair<Int, Int> {
        // Start searching from current position
        var gi = currentGameIdx
        var ti = currentTrackIdx + 1
        
        // Search current game first
        val currentGame = allGames.getOrNull(gi)
        if (currentGame != null) {
            for (i in ti until currentGame.tracks.size) {
                if (currentGame.tracks[i].isFavorite) {
                    return gi to i
                }
            }
        }
        
        // Search remaining games
        for (gIdx in (gi + 1) until allGames.size) {
            val game = allGames[gIdx]
            for (tIdx in game.tracks.indices) {
                if (game.tracks[tIdx].isFavorite) {
                    return gIdx to tIdx
                }
            }
        }
        
        // Wrap around to beginning
        for (gIdx in 0..gi) {
            val game = allGames[gIdx]
            val startIdx = if (gIdx == gi) 0 else 0
            for (tIdx in startIdx until game.tracks.size) {
                if (game.tracks[tIdx].isFavorite) {
                    return gIdx to tIdx
                }
            }
        }
        
        // No favorites found, stay at current position
        return currentGameIdx to currentTrackIdx
    }
    
    private fun findPreviousFavoriteTrack(): Pair<Int, Int> {
        // Start searching backwards from current position
        var gi = currentGameIdx
        var ti = currentTrackIdx - 1
        
        // Search current game first (backwards)
        val currentGame = allGames.getOrNull(gi)
        if (currentGame != null && ti >= 0) {
            for (i in ti downTo 0) {
                if (currentGame.tracks[i].isFavorite) {
                    return gi to i
                }
            }
        }
        
        // Search previous games (backwards)
        for (gIdx in (gi - 1) downTo 0) {
            val game = allGames[gIdx]
            for (tIdx in (game.tracks.size - 1) downTo 0) {
                if (game.tracks[tIdx].isFavorite) {
                    return gIdx to tIdx
                }
            }
        }
        
        // Wrap around to end
        for (gIdx in (allGames.size - 1) downTo gi) {
            val game = allGames[gIdx]
            val startIdx = if (gIdx == gi) (game.tracks.size - 1) else (game.tracks.size - 1)
            for (tIdx in startIdx downTo 0) {
                if (game.tracks[tIdx].isFavorite) {
                    return gIdx to tIdx
                }
            }
        }
        
        // No favorites found, stay at current position
        return currentGameIdx to currentTrackIdx
    }

    fun previousTrack() {
        serviceScope.launch {
            allGames = GameLibrary.getAllGames()
            if (allGames.isEmpty()) return@launch
            
            val favoritesOnly = SettingsManager.isFavoritesOnlyMode(applicationContext)
            
            if (favoritesOnly) {
                val (prevG, prevT) = findPreviousFavoriteTrack()
                loadAndPlay(prevG, prevT)
            } else {
                val nextT = if (currentTrackIdx > 0) currentTrackIdx - 1 else {
                    val prevG = if (currentGameIdx > 0) currentGameIdx - 1 else allGames.size - 1
                    currentGameIdx = prevG
                    (allGames[prevG].tracks.size - 1).coerceAtLeast(0)
                }
                loadAndPlay(currentGameIdx, nextT)
            }
        }
    }

    private fun seekTo(posMs: Long) {
        val samplePos = posMs * SAMPLE_RATE / 1000L
        serviceScope.launch { VgmEngine.seek(samplePos) }
        pausedPositionMs = posMs
        playbackStartTimeMs = SystemClock.elapsedRealtime() - posMs
        updatePlaybackState(if (isPaused) PlaybackStateCompat.STATE_PAUSED
                            else PlaybackStateCompat.STATE_PLAYING)
    }

    // ------- Playback state / notification -------

    private fun currentPositionMs(): Long {
        return if (isPaused) pausedPositionMs
        else SystemClock.elapsedRealtime() - playbackStartTimeMs
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, currentPositionMs(), 1f, SystemClock.elapsedRealtime())
                .setActions(actions)
                .build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID, "VGMP Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VGM music playback controls"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(playing: Boolean): Notification {
        val prevIntent = PendingIntent.getService(this, 1,
            Intent(ACTION_PREV).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playPauseIntent = PendingIntent.getService(this, 2,
            Intent(if (playing) ACTION_PAUSE else ACTION_PLAY).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getService(this, 3,
            Intent(ACTION_NEXT).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 4,
            Intent(ACTION_STOP).setPackage(packageName).setClass(this, VgmPlaybackService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Get album art for notification - use game art or fallback
        val game = currentGame
        val largeIcon = if (game?.artPath?.isNotEmpty() == true && File(game.artPath).exists()) {
            try { BitmapFactory.decodeFile(game.artPath) } catch (e: Exception) { getFallbackArt() }
        } else {
            getFallbackArt()
        }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(largeIcon)
            .setContentTitle(currentTags.displayTitle.ifEmpty { "VGMP" })
            .setContentText(currentTags.displayGame)
            .setSubText(currentTags.displaySystem)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(playing)
            .build()
    }

    private fun updateNotification(playing: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(playing))
    }

    // ------- MediaBrowserServiceCompat (Android Auto) -------

    override fun onBind(intent: Intent?): IBinder? {
        if (SERVICE_INTERFACE == intent?.action) {
            return super.onBind(intent)
        }
        return VgmServiceBinder(this)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(MEDIA_ID_ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        serviceScope.launch {
            allGames = GameLibrary.getAllGames()
            val items = mutableListOf<MediaBrowserCompat.MediaItem>()

            if (parentId == MEDIA_ID_ROOT) {
                // Top-level: list of games
                allGames.forEachIndexed { gi, game ->
                    // Get album art for Android Auto browsing - use game art or fallback
                    val artBitmap: Bitmap? = getScaledArtForAuto(game.artPath)
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId("game/$gi")
                        .setTitle(game.name)
                        .setSubtitle(game.system)
                        .setIconBitmap(artBitmap)
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                }
            } else if (parentId.startsWith("game/")) {
                val gi = parentId.removePrefix("game/").toIntOrNull() ?: run {
                    result.sendResult(items); return@launch
                }
                val game = allGames.getOrNull(gi) ?: run {
                    result.sendResult(items); return@launch
                }
                // Get album art for tracks - use game art or fallback
                val artBitmap: Bitmap? = getScaledArtForAuto(game.artPath)
                game.tracks.forEachIndexed { ti, track ->
                    val durMin = track.durationSamples / SAMPLE_RATE / 60
                    val durSec = (track.durationSamples / SAMPLE_RATE) % 60
                    val subtitle = if (track.durationSamples > 0) "%d:%02d".format(durMin, durSec) else ""
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId("$gi/$ti")
                        .setTitle(track.title)
                        .setSubtitle(subtitle)
                        .setIconBitmap(artBitmap)
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
            }
            result.sendResult(items)
        }
    }

    // Get scaled art for Android Auto (256x256 is recommended size)
    private fun getScaledArtForAuto(artPath: String): Bitmap? {
        val rawBitmap: Bitmap? = if (artPath.isNotEmpty() && File(artPath).exists()) {
            try { BitmapFactory.decodeFile(artPath) } catch (e: Exception) { null }
        } else null

        val sourceBitmap = rawBitmap ?: getFallbackArt() ?: return null

        // Scale to 256x256 for Android Auto
        return Bitmap.createScaledBitmap(sourceBitmap, 256, 256, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY   -> sessionCallback.onPlay()
            ACTION_PAUSE  -> sessionCallback.onPause()
            ACTION_NEXT   -> sessionCallback.onSkipToNext()
            ACTION_PREV   -> sessionCallback.onSkipToPrevious()
            ACTION_STOP   -> { sessionCallback.onStop(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        mediaSession.release()
        serviceScope.cancel()
    }

    // --- Expose state to bound activities ---
    val currentGame: Game? get() = allGames.getOrNull(currentGameIdx)
    val currentTrack: TrackEntity? get() = currentGame?.tracks?.getOrNull(currentTrackIdx)
    val playing: Boolean get() = isPlaying && !isPaused
    val paused:  Boolean get() = isPlaying && isPaused
    fun getMediaSession() = mediaSession
    fun getAllLoadedGames() = allGames
    fun refreshGames() { serviceScope.launch { allGames = GameLibrary.getAllGames() } }
    
    fun updateCurrentTrackFavorite(isFavorite: Boolean) {
        val gameIdx = currentGameIdx
        val trackIdx = currentTrackIdx
        if (gameIdx >= 0 && trackIdx >= 0) {
            val game = allGames.getOrNull(gameIdx) ?: return
            val track = game.tracks.getOrNull(trackIdx) ?: return
            val updatedTrack = track.copy(isFavorite = isFavorite)
            val updatedTracks = game.tracks.toMutableList()
            updatedTracks[trackIdx] = updatedTrack
            val updatedGame = game.copy(tracks = updatedTracks)
            val updatedGames = allGames.toMutableList()
            updatedGames[gameIdx] = updatedGame
            allGames = updatedGames
        }
    }
    
    fun playTrack(game: Game, trackIdx: Int) {
        val gIdx = allGames.indexOfFirst { it.entity.id == game.entity.id }
        if (gIdx >= 0) {
            serviceScope.launch { loadAndPlay(gIdx, trackIdx) }
        }
    }
    
    // --- Fallback album art for Android Auto / media display ---
    private var fallbackArtBitmap: Bitmap? = null
    
    private fun getFallbackArt(): Bitmap? {
        if (fallbackArtBitmap == null) {
            try {
                fallbackArtBitmap = BitmapFactory.decodeResource(resources, R.drawable.vgmp_logo)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load fallback art", e)
            }
        }
        return fallbackArtBitmap
    }
    
    fun setShuffle(mode: ShuffleMode) { shuffleMode = mode }
    fun getShuffle(): ShuffleMode = shuffleMode
    fun setLoop(mode: LoopMode) { loopMode = mode }
    fun getLoop(): LoopMode = loopMode
    
    // Legacy boolean setter for compatibility
    fun setLoopEnabled(enabled: Boolean) { 
        loopMode = if (enabled) LoopMode.TRACK else LoopMode.OFF 
    }
    fun isLoopEnabled(): Boolean = loopMode != LoopMode.OFF
    
    // Endless loop mode - track plays forever without ending
    fun setEndlessLoop(enabled: Boolean) {
        endlessLoopMode = enabled
        serviceScope.launch(Dispatchers.IO) {
            VgmEngine.setEndlessLoop(enabled)
        }
        _playbackState.value = _playbackState.value.copy(endlessLoop = enabled)
    }
    fun getEndlessLoop(): Boolean = endlessLoopMode
    
    /**
     * Get current playback position in milliseconds.
     * This directly calculates the position rather than relying on MediaSession's static position.
     */
    fun getCurrentPositionMs(): Long = currentPositionMs()
    
    /**
     * Check if the current track is an SPC file (endless loop not supported).
     */
    fun isCurrentTrackSpc(): Boolean {
        val track = currentTrack ?: return false
        return track.filePath.endsWith(".spc", ignoreCase = true)
    }
}
