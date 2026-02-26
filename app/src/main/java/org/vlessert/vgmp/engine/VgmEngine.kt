package org.vlessert.vgmp.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kotlin singleton wrapper around the libvgm JNI layer.
 * All calls are synchronized via a Mutex to prevent race conditions
 * between the render loop and UI-driven tag/volume updates.
 */
object VgmEngine {
    private val mutex = Mutex()

    init {
        System.loadLibrary("vgmplayer")
    }

    // ----- Native declarations -----

    @JvmStatic external fun nSetSampleRate(rate: Int)
    @JvmStatic external fun nSetRomPath(path: String)
    @JvmStatic external fun nOpen(path: String): Boolean
    @JvmStatic external fun nClose()
    @JvmStatic external fun nPlay()
    @JvmStatic external fun nStop()
    @JvmStatic external fun nIsEnded(): Boolean
    @JvmStatic external fun nGetTotalSamples(): Long
    @JvmStatic external fun nGetCurrentSample(): Long
    @JvmStatic external fun nSeek(samplePos: Long)

    /**
     * Fill [buffer] with [frames] stereo int16 samples (interleaved L/R).
     * Buffer must have capacity >= frames * 2.
     * Returns number of frames actually written.
     */
    @JvmStatic external fun nFillBuffer(buffer: ShortArray, frames: Int): Int

    /**
     * Returns tag string: "TrkE|||TrkJ|||GmE|||GmJ|||SysE|||SysJ|||AutE|||AutJ|||..."
     */
    @JvmStatic external fun nGetTags(): String
    @JvmStatic external fun nGetSpectrum(magnitudes: FloatArray)

    /** Scan a VGM file's length without loading it as active track */
    @JvmStatic external fun nGetTrackLengthDirect(path: String): Long

    @JvmStatic external fun nGetDeviceCount(): Int
    @JvmStatic external fun nGetDeviceName(id: Int): String
    @JvmStatic external fun nGetDeviceVolume(id: Int): Int
    @JvmStatic external fun nSetDeviceVolume(id: Int, vol: Int)

    // Per-channel muting (libvgm/libgme)
    @JvmStatic external fun nGetChannelCount(): Int
    @JvmStatic external fun nGetChannelDeviceName(index: Int): String
    @JvmStatic external fun nGetChannelName(index: Int): String
    @JvmStatic external fun nIsChannelMuted(index: Int): Boolean
    @JvmStatic external fun nSetChannelMuted(index: Int, muted: Boolean)

    // libgme multi-track support (NSF, GBS, etc.)
    @JvmStatic external fun nGetTrackCount(): Int
    @JvmStatic external fun nSetTrack(trackIndex: Int): Boolean
    @JvmStatic external fun nGetCurrentTrack(): Int
    @JvmStatic external fun nIsMultiTrack(path: String): Boolean
    @JvmStatic external fun nGetTrackLength(path: String, trackIndex: Int): Long

    // Endless loop mode
    @JvmStatic external fun nSetEndlessLoop(enabled: Boolean)
    @JvmStatic external fun nGetEndlessLoop(): Boolean

    // Playback speed control
    @JvmStatic external fun nSetPlaybackSpeed(speed: Double)
    @JvmStatic external fun nGetPlaybackSpeed(): Double

    // KSS direct track info (without opening as active track)
    @JvmStatic external fun nGetKssTrackCountDirect(path: String): Int
    @JvmStatic external fun nGetKssTrackRange(path: String): IntArray  // Returns [minTrack, maxTrack]

    // PSF cache readiness (for async generation)
    @JvmStatic external fun nIsPsfCacheReady(): Boolean

    // ----- Thread-safe wrappers -----

    suspend fun setSampleRate(rate: Int) = mutex.withLock { nSetSampleRate(rate) }
    suspend fun setRomPath(path: String) = mutex.withLock { nSetRomPath(path) }
    suspend fun open(path: String): Boolean = mutex.withLock { nOpen(path) }
    suspend fun close() = mutex.withLock { nClose() }
    suspend fun play() = mutex.withLock { nPlay() }
    suspend fun stop() = mutex.withLock { nStop() }
    suspend fun isEnded(): Boolean = mutex.withLock { nIsEnded() }
    suspend fun getTotalSamples(): Long = mutex.withLock { nGetTotalSamples() }
    suspend fun getCurrentSample(): Long = mutex.withLock { nGetCurrentSample() }
    suspend fun seek(samplePos: Long) = mutex.withLock { nSeek(samplePos) }
    suspend fun fillBuffer(buffer: ShortArray, frames: Int): Int = mutex.withLock { nFillBuffer(buffer, frames) }
    suspend fun getTags(): String = mutex.withLock { nGetTags() }
    suspend fun getSpectrum(magnitudes: FloatArray) = mutex.withLock { nGetSpectrum(magnitudes) }
    suspend fun getTrackLengthDirect(path: String): Long = mutex.withLock { nGetTrackLengthDirect(path) }
    suspend fun getDeviceCount(): Int = mutex.withLock { nGetDeviceCount() }
    suspend fun getDeviceName(id: Int): String = mutex.withLock { nGetDeviceName(id) }
    suspend fun getDeviceVolume(id: Int): Int = mutex.withLock { nGetDeviceVolume(id) }
    suspend fun setDeviceVolume(id: Int, vol: Int) = mutex.withLock { nSetDeviceVolume(id, vol) }

    suspend fun getChannelCount(): Int = mutex.withLock { nGetChannelCount() }
    suspend fun getChannelDeviceName(index: Int): String = mutex.withLock { nGetChannelDeviceName(index) }
    suspend fun getChannelName(index: Int): String = mutex.withLock { nGetChannelName(index) }
    suspend fun isChannelMuted(index: Int): Boolean = mutex.withLock { nIsChannelMuted(index) }
    suspend fun setChannelMuted(index: Int, muted: Boolean) = mutex.withLock { nSetChannelMuted(index, muted) }
    
    // Multi-track support (NSF, GBS, etc.)
    suspend fun getTrackCount(): Int = mutex.withLock { nGetTrackCount() }
    suspend fun setTrack(trackIndex: Int): Boolean = mutex.withLock { nSetTrack(trackIndex) }
    suspend fun getCurrentTrack(): Int = mutex.withLock { nGetCurrentTrack() }
    suspend fun isMultiTrack(path: String): Boolean = mutex.withLock { nIsMultiTrack(path) }
    suspend fun getTrackLength(path: String, trackIndex: Int): Long = mutex.withLock { nGetTrackLength(path, trackIndex) }
    
    // Endless loop mode
    suspend fun setEndlessLoop(enabled: Boolean) = mutex.withLock { nSetEndlessLoop(enabled) }
    suspend fun getEndlessLoop(): Boolean = mutex.withLock { nGetEndlessLoop() }
    
    // Playback speed control
    suspend fun setPlaybackSpeed(speed: Double) = mutex.withLock { nSetPlaybackSpeed(speed) }
    suspend fun getPlaybackSpeed(): Double = mutex.withLock { nGetPlaybackSpeed() }
    
    // KSS direct track info
    suspend fun getKssTrackCountDirect(path: String): Int = mutex.withLock { nGetKssTrackCountDirect(path) }
    suspend fun getKssTrackRange(path: String): IntArray = mutex.withLock { nGetKssTrackRange(path) }

    // PSF cache readiness
    suspend fun isPsfCacheReady(): Boolean = mutex.withLock { nIsPsfCacheReady() }

    /**
     * Parse the raw tag string returned by [nGetTags] into a [VgmTags] object.
     * The format is alternating key-value pairs: "TITLE|||Shop|||TITLE-JPN|||ショップ|||..."
     */
    fun parseTags(raw: String): VgmTags {
        val parts = raw.split("|||")
        val tagMap = mutableMapOf<String, String>()
        
        // Parse alternating key-value pairs
        var i = 0
        while (i + 1 < parts.size) {
            val key = parts[i].trim()
            val value = parts[i + 1].trim()
            if (key.isNotEmpty()) {
                tagMap[key] = value
            }
            i += 2
        }
        
        return VgmTags(
            trackEn  = tagMap["TITLE"] ?: "",
            trackJp  = tagMap["TITLE-JPN"] ?: "",
            gameEn   = tagMap["GAME"] ?: "",
            gameJp   = tagMap["GAME-JPN"] ?: "",
            systemEn = tagMap["SYSTEM"] ?: "",
            systemJp = tagMap["SYSTEM-JPN"] ?: "",
            authorEn = tagMap["ARTIST"] ?: "",
            authorJp = tagMap["ARTIST-JPN"] ?: "",
            date     = tagMap["DATE"] ?: "",
            creator  = tagMap["ENCODED_BY"] ?: "",
            notes    = tagMap["COMMENT"] ?: ""
        )
    }

    /** Duration in seconds from total samples and sample rate */
    fun durationSeconds(totalSamples: Long, sampleRate: Int): Long =
        if (sampleRate > 0) totalSamples / sampleRate else 0L

    fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}

data class VgmTags(
    val trackEn:  String = "",
    val trackJp:  String = "",
    val gameEn:   String = "",
    val gameJp:   String = "",
    val systemEn: String = "",
    val systemJp: String = "",
    val authorEn: String = "",
    val authorJp: String = "",
    val date:     String = "",
    val creator:  String = "",
    val notes:    String = ""
) {
    val displayTitle: String get() = when {
        trackEn.isNotEmpty() && trackJp.isNotEmpty() && trackEn != trackJp -> "$trackEn ($trackJp)"
        trackEn.isNotEmpty() -> trackEn
        else -> trackJp.ifEmpty { "Unknown Track" }
    }
    val displayGame: String get() = when {
        gameEn.isNotEmpty() && gameJp.isNotEmpty() && gameEn != gameJp -> "$gameEn ($gameJp)"
        gameEn.isNotEmpty() -> gameEn
        else -> gameJp.ifEmpty { "Unknown Game" }
    }
    val displaySystem: String get() = when {
        systemEn.isNotEmpty() && systemJp.isNotEmpty() && systemEn != systemJp -> "$systemEn ($systemJp)"
        systemEn.isNotEmpty() -> systemEn
        else -> systemJp
    }
    val displayAuthor: String get() = when {
        authorEn.isNotEmpty() && authorJp.isNotEmpty() && authorEn != authorJp -> "$authorEn ($authorJp)"
        authorEn.isNotEmpty() -> authorEn
        else -> authorJp
    }
}
