package com.soundify.app.editor

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.pow

/** One source file queued for preview playback. */
data class PlaybackItem(val clip: EditorClip, val file: File)

/**
 * Plays editor clips through [MediaPlayer] without modifying them.
 *
 * The items are played in queue order. A single clip is a queue of one. For
 * each item the player seeks to [EditorClip.trimStartMs], polls every
 * [POLL_MS] and pauses at [EditorClip.trimEndMs]; at the end of the last clip
 * it stops and calls [onFinished] instead of looping. The reported
 * [positionMs] is the MediaPlayer source position (in milliseconds, clamped to
 * the current trim range); the UI derives clip-relative time by subtracting
 * [EditorClip.trimStartMs].
 *
 * Preview is intentionally approximate: fades, crossfades, EQ, denoise,
 * compressor and clarity are not previewed. They are applied on export, as is
 * any gain boost above 0 dB. Speed and pitch go through [PlaybackParams]; if
 * the device rejects a setting, playback falls back to 1x and
 * [previewUnavailable] reports it. Speeds other than 1x change the wall-clock
 * length but not the reported source position, so trim boundaries stay exact.
 * The 50 ms poll can let up to one poll interval of audio be heard after the
 * trim end, though [positionMs] never reports past it.
 *
 * All methods must be called on the main thread: [MediaPlayer] is created
 * there, and its callbacks and the poll [Handler] run on the main [Looper].
 * [release] stops playback and frees the native player; it is safe to call more
 * than once, and the instance can be loaded again afterwards.
 */
class EditorPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private val _positionMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(0)
    private val _previewUnavailable = MutableStateFlow(false)
    private val _isPlaying = MutableStateFlow(false)

    /** Current MediaPlayer source position in milliseconds, clamped to the trim. */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** Index of the item currently being prepared or played. */
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** True once this device has rejected a speed or pitch preview setting. */
    val previewUnavailable: StateFlow<Boolean> = _previewUnavailable.asStateFlow()

    /** True while audio is actually playing (not while paused or preparing). */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Called with the queue index when an item becomes current. */
    var onClipChanged: ((Int) -> Unit)? = null

    /** Called when the last clip has finished, or a playback error stops the queue. */
    var onFinished: (() -> Unit)? = null

    private var queue: PlaybackQueue? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playing = false
    private var finishing = false
    private var sequenceFinished = false
    private var gainDb = 0f
    private var speed = 1f
    private var pitchSemitones = 0
    private var pendingSeekMs: Long? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!playing) return
            val player = player ?: return
            val clip = queue?.current?.clip ?: return
            val position = runCatching { player.currentPosition.toLong() }.getOrNull() ?: return
            _positionMs.value = position.coerceIn(clip.trimStartMs, clip.trimEndMs)
            if (position >= clip.trimEndMs) {
                finishCurrent()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    /**
     * Replaces the queue with [items] and starts playing at [startIndex].
     * [startIndex] is coerced into the list bounds.
     */
    fun load(items: List<PlaybackItem>, startIndex: Int = 0) {
        checkMainThread()
        require(items.isNotEmpty()) { "At least one playback item is required" }
        stopCurrent()
        queue = PlaybackQueue(items, startIndex)
        startCurrent()
    }

    /** Convenience for a queue of one. */
    fun load(clip: EditorClip, file: File) {
        load(listOf(PlaybackItem(clip, file)))
    }

    /**
     * Sets the preview gain in decibels. Linear gain is capped at unity, so
     * boosts above 0 dB are applied on export instead.
     */
    fun setGainDb(gainDb: Float) {
        checkMainThread()
        this.gainDb = gainDb
        val player = player ?: return
        if (!prepared) return
        val gain = previewGain(gainDb)
        runCatching { player.setVolume(gain, gain) }
    }

    /** Sets the preview speed. Values the device rejects fall back to 1x. */
    fun setSpeed(speed: Float) {
        checkMainThread()
        this.speed = speed
        updatePlaybackParams()
    }

    /** Sets the preview pitch shift in semitones. Rejected values fall back to 1x. */
    fun setPitchSemitones(semitones: Int) {
        checkMainThread()
        pitchSemitones = semitones
        updatePlaybackParams()
    }

    /**
     * Seeks within the current clip, clamped to
     * [EditorClip.trimStartMs]..[EditorClip.trimEndMs].
     */
    fun seekToSourceMs(sourceMs: Long) {
        checkMainThread()
        val clip = queue?.current?.clip ?: return
        val target = sourceMs.coerceIn(clip.trimStartMs, clip.trimEndMs)
        if (target < clip.trimEndMs) sequenceFinished = false
        val player = player
        if (player != null && prepared) {
            pendingSeekMs = null
            runCatching { player.seekTo(target.toInt()) }
        } else {
            // prepareAsync may not have returned yet; remember the seek so
            // handlePrepared lands on it instead of the trim start.
            pendingSeekMs = target
        }
        _positionMs.value = target
    }

    /** Pauses playback; the position is retained. */
    fun pause() {
        checkMainThread()
        if (!playing) return
        val player = player ?: return
        handler.removeCallbacks(pollRunnable)
        runCatching { player.pause() }
        playing = false
        _isPlaying.value = false
        refreshPosition()
    }

    /**
     * Resumes from the current position. If playback had reached the trim end,
     * it restarts from the trim start.
     */
    fun resume() {
        checkMainThread()
        if (playing) return
        val player = player ?: return
        if (!prepared) return
        val clip = queue?.current?.clip ?: return
        if (_positionMs.value >= clip.trimEndMs) {
            runCatching { player.seekTo(clip.trimStartMs.toInt()) }
            _positionMs.value = clip.trimStartMs
            sequenceFinished = false
        }
        startPlayback(player)
    }

    /** Stops playback, cancels polling and releases the MediaPlayer. */
    fun release() {
        checkMainThread()
        stopCurrent()
        queue = null
        gainDb = 0f
        speed = 1f
        pitchSemitones = 0
        _positionMs.value = 0L
        _currentIndex.value = 0
        _previewUnavailable.value = false
    }

    private fun startCurrent() {
        val queue = queue ?: return
        val item = queue.current
        releasePlayer()
        prepared = false
        pendingSeekMs = null
        sequenceFinished = false
        _currentIndex.value = queue.index
        _positionMs.value = item.clip.trimStartMs
        val player = MediaPlayer()
        this.player = player
        player.setOnPreparedListener { preparedPlayer ->
            handlePrepared(preparedPlayer)
        }
        player.setOnCompletionListener { completed ->
            if (this.player === completed) finishCurrent()
        }
        player.setOnErrorListener { failed, _, _ ->
            if (this.player === failed) handlePlaybackFailure()
            true
        }
        try {
            player.setDataSource(item.file.absolutePath)
            player.prepareAsync()
        } catch (error: Exception) {
            handlePlaybackFailure()
            return
        }
        onClipChanged?.invoke(queue.index)
    }

    private fun handlePrepared(player: MediaPlayer) {
        if (this.player !== player) return
        val clip = queue?.current?.clip ?: return
        prepared = true
        val gain = previewGain(gainDb)
        runCatching { player.setVolume(gain, gain) }
        applyPlaybackParams(player)
        val target = pendingSeekMs ?: clip.trimStartMs
        pendingSeekMs = null
        runCatching { player.seekTo(target.toInt()) }
        _positionMs.value = target
        startPlayback(player)
    }

    private fun startPlayback(player: MediaPlayer) {
        try {
            player.start()
        } catch (error: IllegalStateException) {
            handlePlaybackFailure()
            return
        }
        playing = true
        _isPlaying.value = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun finishCurrent() {
        if (finishing || sequenceFinished) return
        val queue = queue ?: return
        finishing = true
        handler.removeCallbacks(pollRunnable)
        playing = false
        _isPlaying.value = false
        val clip = queue.current.clip
        val player = player
        runCatching { player?.pause() }
        _positionMs.value = clip.trimEndMs
        if (queue.advance()) {
            startCurrent()
        } else {
            sequenceFinished = true
            runCatching { player?.seekTo(clip.trimEndMs.toInt()) }
            onFinished?.invoke()
        }
        finishing = false
    }

    private fun handlePlaybackFailure() {
        handler.removeCallbacks(pollRunnable)
        playing = false
        prepared = false
        sequenceFinished = true
        _isPlaying.value = false
        releasePlayer()
        queue?.current?.clip?.let { _positionMs.value = it.trimEndMs }
        onFinished?.invoke()
    }

    private fun updatePlaybackParams() {
        if (speed == 1f && pitchSemitones == 0) {
            _previewUnavailable.value = false
            return
        }
        val player = player ?: return
        if (!prepared) return
        applyPlaybackParams(player)
    }

    private fun applyPlaybackParams(player: MediaPlayer) {
        if (speed == 1f && pitchSemitones == 0) {
            _previewUnavailable.value = false
            return
        }
        try {
            player.playbackParams = PlaybackParams()
                .setSpeed(speed)
                .setPitch(pitchRatio(pitchSemitones))
            _previewUnavailable.value = false
        } catch (error: IllegalArgumentException) {
            fallBackToNormalSpeed(player)
        } catch (error: IllegalStateException) {
            fallBackToNormalSpeed(player)
        }
    }

    private fun fallBackToNormalSpeed(player: MediaPlayer) {
        _previewUnavailable.value = true
        runCatching {
            player.playbackParams = PlaybackParams().setSpeed(1f).setPitch(1f)
        }
    }

    private fun refreshPosition() {
        val player = player ?: return
        val clip = queue?.current?.clip ?: return
        val position = runCatching { player.currentPosition.toLong() }.getOrNull() ?: return
        _positionMs.value = position.coerceIn(clip.trimStartMs, clip.trimEndMs)
    }

    private fun stopCurrent() {
        handler.removeCallbacks(pollRunnable)
        finishing = false
        playing = false
        prepared = false
        sequenceFinished = false
        pendingSeekMs = null
        _isPlaying.value = false
        releasePlayer()
    }

    private fun releasePlayer() {
        val player = player ?: return
        this.player = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "EditorPlayer must be used from the main thread"
        }
    }

    private companion object {
        const val POLL_MS = 50L
    }
}

/**
 * Ordered queue for preview playback. Kept free of Android types so the
 * advance rules can be unit tested.
 */
internal class PlaybackQueue(items: List<PlaybackItem>, startIndex: Int) {

    private val items = items.toList()

    init {
        require(this.items.isNotEmpty()) { "At least one playback item is required" }
    }

    var index: Int = startIndex.coerceIn(0, this.items.lastIndex)
        private set

    val current: PlaybackItem get() = items[index]

    /** Moves to the next item and returns true, or stays put at the last item. */
    fun advance(): Boolean {
        if (index >= items.lastIndex) return false
        index++
        return true
    }
}

/** Linear preview gain; positive decibels are capped at unity. */
internal fun previewGain(gainDb: Float): Float {
    val gain = 10.0.pow(gainDb / 20.0).toFloat()
    return if (gain.isNaN()) 1f else gain.coerceIn(0f, 1f)
}

/** Pitch multiplier for a semitone offset; invalid factors mean no shift. */
internal fun pitchRatio(semitones: Int): Float {
    val ratio = 2.0.pow(semitones / 12.0).toFloat()
    return if (ratio.isFinite() && ratio > 0f) ratio else 1f
}
