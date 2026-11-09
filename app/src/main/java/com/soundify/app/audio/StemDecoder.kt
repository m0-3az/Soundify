package com.soundify.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Decodes one stem on its own thread and pushes interleaved stereo float PCM
 * into [ring]. Seeking is handled inside the decode loop so the mixer can
 * request a new position without reconstructing the codec.
 */
internal class StemDecoder(
    private val file: File,
    private val targetSampleRate: Int,
    private val ring: PcmRingBuffer
) {

    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var seekRequest: Long? = null

    @Volatile
    private var seekGeneration = 0
    private var seekAckGeneration = 0
    private val seekLock = Object()

    @Volatile
    var error: Throwable? = null
        private set

    private var eos = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ decodeLoop() }, "soundify-decode-${file.name}").apply { start() }
    }

    fun seekTo(sample: Long) {
        if (!running) return
        val generation: Int
        synchronized(seekLock) {
            seekRequest = sample.coerceAtLeast(0L)
            seekGeneration += 1
            generation = seekGeneration
            ring.clear()
            ring.setEof(false)
            eos = false
            val deadline = System.currentTimeMillis() + 3000
            while (seekAckGeneration < generation && running && System.currentTimeMillis() < deadline) {
                seekLock.wait(50)
            }
        }
    }

    fun stop() {
        running = false
        ring.close()
        thread?.join(1500)
        if (thread?.isAlive == true) thread?.interrupt()
        thread = null
    }

    private fun decodeLoop() {
        var session: Session? = null
        try {
            session = openSession()
            while (running) {
                val request = seekRequest
                if (request != null) {
                    seekRequest = null
                    eos = false
                    ring.clear()
                    ring.setEof(false)
                    val actualStart = seekSession(session, request)
                    session.skipFrames = (request - actualStart).coerceAtLeast(0L)
                    synchronized(seekLock) {
                        seekAckGeneration = seekGeneration
                        seekLock.notifyAll()
                    }
                    continue
                }

                if (eos) {
                    Thread.sleep(50)
                    continue
                }

                val finished = decodeChunk(session)
                if (finished) {
                    eos = true
                    ring.setEof(true)
                }
            }
        } catch (t: Throwable) {
            if (running) {
                error = t
                ring.setEof(true)
                eos = true
            }
        } finally {
            session?.close()
        }
    }

    private fun openSession(): Session {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = index
                format = candidate
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "No audio track in ${file.name}" }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val raw = mime == "audio/raw"
        val codec = if (raw) {
            null
        } else {
            MediaCodec.createDecoderByType(mime).also {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                it.configure(format, null, null, 0)
                it.start()
            }
        }

        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val rawBuffer = if (raw) ByteBuffer.allocateDirect(256 * 1024) else null
        return Session(extractor, codec, raw, channels, rate, pcmFloat, rawBuffer)
    }

    private fun seekSession(session: Session, sample: Long): Long {
        val timeUs = sample * 1_000_000L / targetSampleRate
        session.extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        session.codec?.flush()
        session.inputDone = false
        session.skipFrames = 0L
        ring.clear()
        ring.setEof(false)
        val actualUs = session.extractor.sampleTime
        return if (actualUs >= 0) {
            actualUs * targetSampleRate / 1_000_000L
        } else {
            sample
        }
    }

    private fun decodeChunk(session: Session): Boolean {
        if (session.raw) return decodeRawChunk(session)
        val codec = session.codec ?: return true

        var fed = 0
        while (fed < 4 && !session.inputDone) {
            val inputIndex = codec.dequeueInputBuffer(5_000)
            if (inputIndex < 0) break
            val input = codec.getInputBuffer(inputIndex) ?: break
            input.clear()
            val size = session.extractor.readSampleData(input, 0)
            if (size < 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                session.inputDone = true
            } else {
                codec.queueInputBuffer(inputIndex, 0, size, session.extractor.sampleTime, 0)
                session.extractor.advance()
            }
            fed++
        }

        val info = MediaCodec.BufferInfo()
        var drained = 0
        var wrote = false
        while (drained < 8) {
            val outputIndex = codec.dequeueOutputBuffer(info, if (drained == 0) 5_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    session.channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    session.rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    session.pcmFloat = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                        AudioFormat.ENCODING_PCM_FLOAT
                    if (session.rate != targetSampleRate) {
                        error("Unsupported decoded sample rate ${session.rate} for ${file.name}")
                    }
                }

                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (session.rate != targetSampleRate) {
                            error("Unsupported decoded sample rate ${session.rate} for ${file.name}")
                        }
                        if (writePcm(session, buffer, info.size)) wrote = true
                    }
                    val ended = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (ended) return true
                }

                else -> {
                    // INFO_TRY_AGAIN_LATER or INFO_OUTPUT_BUFFERS_CHANGED
                }
            }
            drained++
            if (wrote && drained > 1) break
            if (seekRequest != null) break
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
        }
        return false
    }

    private fun decodeRawChunk(session: Session): Boolean {
        val buffer = session.rawBuffer ?: return true
        buffer.clear()
        val size = session.extractor.readSampleData(buffer, 0)
        if (size < 0) return true
        buffer.position(0)
        buffer.limit(size)
        if (session.rate != targetSampleRate) {
            error("Unsupported raw sample rate ${session.rate} for ${file.name}")
        }
        writePcm(session, buffer, size)
        session.extractor.advance()
        return false
    }

    private fun writePcm(session: Session, buffer: ByteBuffer, byteCount: Int): Boolean {
        val channels = session.channels
        if (channels <= 0) return false
        val bytesPerSample = if (session.pcmFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return false

        var frames = byteCount / frameBytes
        if (frames <= 0) return false

        val skip = min(session.skipFrames, frames.toLong()).toInt()
        session.skipFrames -= skip
        frames -= skip
        if (frames <= 0) return false

        val output = FloatArray(frames * 2)
        if (session.pcmFloat) {
            val source = buffer.asFloatBuffer()
            source.position(source.position() + skip * channels)
            for (index in 0 until frames) {
                val left = source.get()
                val right = if (channels >= 2) source.get() else left
                if (channels > 2) {
                    source.position(source.position() + (channels - 2))
                }
                output[index * 2] = left
                output[index * 2 + 1] = right
            }
        } else {
            val source = buffer.asShortBuffer()
            source.position(source.position() + skip * channels)
            for (index in 0 until frames) {
                val left = source.get() / 32768f
                val right = if (channels >= 2) source.get() / 32768f else left
                if (channels > 2) {
                    source.position(source.position() + (channels - 2))
                }
                output[index * 2] = left
                output[index * 2 + 1] = right
            }
        }

        if (seekRequest != null) return false
        return ring.write(output, frames)
    }

    private class Session(
        val extractor: MediaExtractor,
        val codec: MediaCodec?,
        val raw: Boolean,
        var channels: Int,
        var rate: Int,
        var pcmFloat: Boolean,
        val rawBuffer: ByteBuffer?
    ) {
        var inputDone = false
        var skipFrames = 0L

        fun close() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
