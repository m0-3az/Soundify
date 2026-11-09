package com.soundify.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.soundify.app.data.Project
import com.soundify.app.data.Stem
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Plays all stems of a project through one AudioTrack. Each stem is decoded on
 * its own thread and mixed in the mixer thread, so the output is sample-aligned.
 */
class StemMixer(
    private val project: Project,
    private val onPosition: (Long) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit
) {

    private val sampleRate = project.sampleRate
    private val durationSamples = project.durationMs * sampleRate / 1000L
    private val frameBlock = 1024
    private val ringCapacityFrames = sampleRate * 4
    private val prebufferFrames = sampleRate / 2

    private val channels = mutableListOf<Channel>()
    private var audioTrack: AudioTrack? = null
    private var mixerThread: Thread? = null
    private val lock = Object()

    @Volatile
    private var running = false

    @Volatile
    private var playing = false

    @Volatile
    private var seekRequest = -1L

    @Volatile
    private var masterGain = project.masterGain

    @Volatile
    private var loopEnabled = project.loopEnabled

    @Volatile
    private var loopStartSample = project.loopStartMs * sampleRate / 1000L

    @Volatile
    private var loopEndSample = project.loopEndMs * sampleRate / 1000L

    private var positionSamples = 0L
    private var finished = false

    init {
        project.stems.sortedBy { it.sortOrder }.forEach { stem ->
            val ring = PcmRingBuffer(ringCapacityFrames)
            channels += Channel(
                stem = stem,
                ring = ring,
                decoder = StemDecoder(File(stem.filePath), sampleRate, ring),
                offsetSamples = stem.offsetMs * sampleRate / 1000L
            )
        }
    }

    fun start(initialPositionMs: Long = 0L) {
        if (running) return
        if (channels.isEmpty()) {
            onError("No stems to play")
            return
        }
        try {
            audioTrack = createAudioTrack()
        } catch (t: Throwable) {
            onError("Audio output unavailable: ${t.message}")
            return
        }

        running = true
        finished = false
        channels.forEach { it.decoder.start() }
        val startSample = initialPositionMs.coerceIn(0L, project.durationMs) * sampleRate / 1000L
        positionSamples = startSample
        seekRequest = startSample
        mixerThread = Thread({ mixLoop() }, "soundify-mixer").apply { start() }
    }

    fun play() {
        if (!running) {
            start(positionSamples * 1000L / sampleRate)
            playing = true
            return
        }
        if (finished) {
            finished = false
            seekRequest = 0L
        }
        playing = true
        audioTrack?.play()
        synchronized(lock) { lock.notifyAll() }
    }

    fun pause() {
        playing = false
        audioTrack?.pause()
        synchronized(lock) { lock.notifyAll() }
    }

    fun seekTo(positionMs: Long) {
        seekRequest = positionMs.coerceIn(0L, project.durationMs) * sampleRate / 1000L
    }

    fun setGain(stemId: String, gain: Float) {
        channel(stemId)?.gain = gain.coerceIn(0f, 2f)
    }

    fun setPan(stemId: String, pan: Float) {
        channel(stemId)?.pan = pan.coerceIn(-1f, 1f)
    }

    fun setMuted(stemId: String, muted: Boolean) {
        channel(stemId)?.muted = muted
    }

    fun setSoloed(stemId: String, soloed: Boolean) {
        channel(stemId)?.soloed = soloed
    }

    fun setMasterGain(gain: Float) {
        masterGain = gain.coerceIn(0f, 2f)
    }

    fun setLoop(enabled: Boolean, startMs: Long, endMs: Long) {
        loopEnabled = enabled
        loopStartSample = startMs.coerceAtLeast(0L) * sampleRate / 1000L
        loopEndSample = endMs.coerceAtLeast(0L) * sampleRate / 1000L
    }

    fun setOffset(stemId: String, offsetMs: Long) {
        val channel = channel(stemId) ?: return
        channel.offsetSamples = offsetMs.coerceIn(-500L, 500L) * sampleRate / 1000L
        seekTo(positionSamples * 1000L / sampleRate)
    }

    fun currentPositionMs(): Long = positionSamples * 1000L / sampleRate

    fun release() {
        running = false
        playing = false
        synchronized(lock) { lock.notifyAll() }
        channels.forEach { it.ring.close() }
        mixerThread?.let {
            it.interrupt()
            runCatching { it.join(1500) }
        }
        mixerThread = null
        channels.forEach { it.decoder.stop() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    private fun mixLoop() {
        val mix = FloatArray(frameBlock * 2)
        val stemBuffer = FloatArray(frameBlock * 2)
        val shortBuffer = ShortArray(frameBlock * 2)

        while (running) {
            val request = seekRequest
            if (request >= 0) {
                seekRequest = -1L
                performSeek(request)
                continue
            }

            if (!playing) {
                synchronized(lock) { lock.wait(50) }
                continue
            }

            val anySolo = channels.any { it.soloed }
            val errorChannel = channels.firstOrNull { it.decoder.error != null && !it.errorReported }
            if (errorChannel != null) {
                errorChannel.errorReported = true
                onError(
                    "Could not decode ${errorChannel.stem.displayName}: " +
                        (errorChannel.decoder.error?.message ?: "unknown error")
                )
            }

            if (loopEnabled && loopEndSample > loopStartSample && positionSamples >= loopEndSample) {
                performSeek(loopStartSample)
                continue
            }

            if (positionSamples >= durationSamples) {
                finish()
                continue
            }

            val frames = min(frameBlock.toLong(), durationSamples - positionSamples).toInt()
            mix.fill(0f)

            for (channel in channels) {
                val read = channel.ring.read(stemBuffer, frames, zeroFill = true)
                if (read < 0) continue

                val gain = if (channel.muted || (anySolo && !channel.soloed)) {
                    0f
                } else {
                    channel.gain.coerceIn(0f, 2f)
                }
                val (leftGain, rightGain) = MixMath.panGains(channel.pan)
                val skip = channel.skipFrames
                for (index in 0 until frames) {
                    if (index >= skip && gain > 0f) {
                        mix[index * 2] += stemBuffer[index * 2] * gain * leftGain
                        mix[index * 2 + 1] += stemBuffer[index * 2 + 1] * gain * rightGain
                    }
                }
                if (skip > 0L) {
                    channel.skipFrames = max(0L, skip - frames)
                }
            }

            for (index in 0 until frames * 2) {
                val sample = (mix[index] * masterGain).coerceIn(-1f, 1f)
                shortBuffer[index] = (sample * 32767f).toInt().toShort()
            }

            val written = audioTrack?.write(shortBuffer, 0, frames * 2) ?: -1
            if (written < 0) {
                onError("Audio output failed")
                break
            }

            positionSamples += frames
            onPosition(positionSamples * 1000L / sampleRate)
        }
    }

    private fun performSeek(sample: Long) {
        val track = audioTrack ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        positionSamples = sample.coerceIn(0L, durationSamples)
        channels.forEach { channel ->
            val target = (positionSamples - channel.offsetSamples).coerceAtLeast(0L)
            channel.skipFrames = (channel.offsetSamples - positionSamples).coerceAtLeast(0L)
            channel.decoder.seekTo(target)
        }
        waitForPrebuffer()
        if (playing && running) {
            runCatching { track.play() }
        }
        onPosition(positionSamples * 1000L / sampleRate)
    }

    private fun waitForPrebuffer() {
        val deadline = System.currentTimeMillis() + 3000
        while (running && System.currentTimeMillis() < deadline) {
            val ready = channels.all {
                it.ring.availableFrames() >= prebufferFrames ||
                    it.ring.isEof() ||
                    it.decoder.error != null
            }
            if (ready) return
            Thread.sleep(10)
        }
    }

    private fun finish() {
        finished = true
        playing = false
        runCatching { audioTrack?.pause() }
        onPosition(project.durationMs)
        onFinished()
    }

    private fun channel(stemId: String): Channel? = channels.firstOrNull { it.stem.id == stemId }

    private fun createAudioTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) error("Unsupported audio output configuration")
        val bufferSize = maxOf(minBuffer * 2, frameBlock * 16)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private class Channel(
        val stem: Stem,
        val ring: PcmRingBuffer,
        val decoder: StemDecoder,
        @Volatile var offsetSamples: Long
    ) {
        @Volatile
        var gain: Float = stem.gain

        @Volatile
        var pan: Float = stem.pan

        @Volatile
        var muted: Boolean = stem.muted

        @Volatile
        var soloed: Boolean = stem.soloed

        var skipFrames: Long = 0L
        var errorReported: Boolean = false
    }
}
