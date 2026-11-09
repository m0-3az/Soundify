package com.soundify.app.audio

import com.soundify.app.data.Project
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the current project to a 16-bit PCM WAV file using the same mix
 * math as live playback. Runs off the main thread.
 */
class WavExporter(private val project: Project) {

    fun export(output: File, onProgress: (Float) -> Unit): File {
        val sampleRate = project.sampleRate
        val totalFrames = project.durationMs * sampleRate / 1000L
        val block = 4096
        val channels = project.stems.sortedBy { it.sortOrder }.map { stem ->
            val ring = PcmRingBuffer(sampleRate * 4)
            ExportChannel(
                stem = stem,
                ring = ring,
                decoder = StemDecoder(File(stem.filePath), sampleRate, ring),
                offsetSamples = stem.offsetMs * sampleRate / 1000L
            )
        }
        if (channels.isEmpty()) error("No stems to export")

        val writer = WavWriter(output, sampleRate)
        try {
            channels.forEach { it.decoder.start() }
            channels.forEach { channel ->
                val target = (-channel.offsetSamples).coerceAtLeast(0L)
                channel.skipFrames = channel.offsetSamples.coerceAtLeast(0L)
                channel.decoder.seekTo(target)
            }
            waitForPrebuffer(channels, sampleRate / 2)

            val stemBuffer = FloatArray(block * 2)
            val mix = FloatArray(block * 2)
            val anySolo = project.stems.any { it.soloed }
            var position = 0L

            while (position < totalFrames) {
                val frames = min(block.toLong(), totalFrames - position).toInt()
                mix.fill(0f)

                for (channel in channels) {
                    val read = channel.ring.read(stemBuffer, frames, zeroFill = true)
                    if (read < 0) continue

                    val gain = MixMath.effectiveGain(channel.stem, anySolo)
                    val (leftGain, rightGain) = MixMath.panGains(channel.stem.pan)
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
                    mix[index] = (mix[index] * project.masterGain).coerceIn(-1f, 1f)
                }
                writer.writeInterleaved(mix, frames)
                position += frames
                onProgress((position.toFloat() / totalFrames).coerceIn(0f, 1f))
            }
        } finally {
            channels.forEach { it.decoder.stop() }
            writer.close()
        }
        return output
    }

    private fun waitForPrebuffer(channels: List<ExportChannel>, frames: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val ready = channels.all {
                it.ring.availableFrames() >= frames || it.ring.isEof() || it.decoder.error != null
            }
            if (ready) return
            Thread.sleep(10)
        }
    }

    private class ExportChannel(
        val stem: com.soundify.app.data.Stem,
        val ring: PcmRingBuffer,
        val decoder: StemDecoder,
        val offsetSamples: Long
    ) {
        var skipFrames: Long = 0L
    }
}
