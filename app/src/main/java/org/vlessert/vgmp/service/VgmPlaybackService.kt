package org.vlessert.vgmp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import kotlinx.coroutines.flow.asStateFlow
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.VgmServiceBinder
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.engine.VgmTags
import org.vlessert.vgmp.library.Game
import org.vlessert.vgmp.library.GameLibrary
import org.vlessert.vgmp.library.TrackEntity
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
    }

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
    private var randomEnabled = false
    private var loopEnabled   = false
    private var currentTags = VgmTags()
    private var trackDurationMs = 0L

    // Render thread
    private var renderJob: Job? = null
    private val renderBuffer = ShortArray(BUFFER_FRAMES * 2)  // interleaved stereo

    private val _playbackState = MutableStateFlow<PlaybackInfo>(PlaybackInfo())
    val playbackInfo = _playbackState.asStateFlow()

    data class PlaybackInfo(
        val playing: Boolean = false,
        val paused: Boolean = false,
        val gameIdx: Int = -1,
        val trackIdx: Int = -1,
        val track: TrackEntity? = null
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
        }
    }

    private suspend fun loadBundledAssets() {
        val assetZips = try {
            assets.list("music")?.map { "music/$it" } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        GameLibrary.loadBundledAssetsIfNeeded(this, assetZips)
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
            loopEnabled = repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            randomEnabled = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL
        }
    }

    // ------- Audio focus setup -------
    
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioTrack?.setVolume(1.0f)
                if (shouldPlayAfterFocusGain) resumeOrPlay()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                shouldPlayAfterFocusGain = false
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
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
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
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to get audio focus")
            return
        }

        val opened = VgmEngine.open(track.filePath)
        if (!opened) {
            Log.e(TAG, "Failed to open ${track.filePath}")
            return
        }

        // Parse tags
        currentTags = VgmEngine.parseTags(VgmEngine.getTags())
        trackDurationMs = if (track.durationSamples > 0)
            track.durationSamples * 1000L / SAMPLE_RATE else 0L

        // Album art
        val artBitmap = if (game.artPath.isNotEmpty() && File(game.artPath).exists()) {
            try { BitmapFactory.decodeFile(game.artPath) } catch (e: Exception) { null }
        } else null

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
        _playbackState.value = PlaybackInfo(true, false, currentGameIdx, currentTrackIdx, track)
    }

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
                        audioTrack?.write(renderBuffer, 0, framesWritten * 2)
                    }
                    // Check if track ended
                    if (VgmEngine.isEnded()) {
                        onTrackEnded()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Render loop error", e)
            }
        }
    }

    private fun stopRenderJob() {
        renderJob?.cancel()
        renderJob = null
        audioTrack?.pause()
        audioTrack?.flush()
    }

    private suspend fun onTrackEnded() {
        if (loopEnabled) {
            // Restart same track
            val game = allGames.getOrNull(currentGameIdx) ?: return
            val track = game.tracks.getOrNull(currentTrackIdx) ?: return
            startTrack(game, track)
        } else {
            nextTrack()
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
        serviceScope.launch {
            allGames = GameLibrary.getAllGames()
            if (allGames.isEmpty()) return@launch
            val (nextG, nextT) = if (randomEnabled) {
                val gi = (0 until allGames.size).random()
                val ti = (0 until (allGames[gi].tracks.size.coerceAtLeast(1))).random()
                gi to ti
            } else {
                val game = allGames.getOrNull(currentGameIdx)
                if (game != null && currentTrackIdx + 1 < game.tracks.size) {
                    currentGameIdx to currentTrackIdx + 1
                } else {
                    val nextG2 = (currentGameIdx + 1) % allGames.size
                    nextG2 to 0
                }
            }
            loadAndPlay(nextG, nextT)
        }
    }

    fun previousTrack() {
        serviceScope.launch {
            allGames = GameLibrary.getAllGames()
            if (allGames.isEmpty()) return@launch
            val nextT = if (currentTrackIdx > 0) currentTrackIdx - 1 else {
                val prevG = if (currentGameIdx > 0) currentGameIdx - 1 else allGames.size - 1
                currentGameIdx = prevG
                (allGames[prevG].tracks.size - 1).coerceAtLeast(0)
            }
            loadAndPlay(currentGameIdx, nextT)
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

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
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
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId("game/$gi")
                        .setTitle(game.name)
                        .setSubtitle(game.system)
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
                game.tracks.forEachIndexed { ti, track ->
                    val durMin = track.durationSamples / SAMPLE_RATE / 60
                    val durSec = (track.durationSamples / SAMPLE_RATE) % 60
                    val subtitle = if (track.durationSamples > 0) "%d:%02d".format(durMin, durSec) else ""
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId("$gi/$ti")
                        .setTitle(track.title)
                        .setSubtitle(subtitle)
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
            }
            result.sendResult(items)
        }
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
    val audioSessionId: Int get() = audioTrack?.audioSessionId ?: 0
    fun getAllLoadedGames() = allGames
    fun refreshGames() { serviceScope.launch { allGames = GameLibrary.getAllGames() } }
    fun playTrack(game: Game, trackIdx: Int) {
        val gIdx = allGames.indexOfFirst { it.entity.id == game.entity.id }
        if (gIdx >= 0) {
            serviceScope.launch { loadAndPlay(gIdx, trackIdx) }
        }
    }
    fun setRandom(enabled: Boolean) { randomEnabled = enabled }
    fun setLoop(enabled: Boolean) { loopEnabled = enabled }
}
