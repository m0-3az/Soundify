package com.soundify.app.editor

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.soundify.app.cleanvideo.Ffmpeg
import com.soundify.app.cleanvideo.WavStreamReader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

/** A min/max waveform envelope and the source duration it describes. */
class PeakData(
    val peaks: FloatArray,
    val durationMs: Long,
) {
    val buckets: Int get() = peaks.size / 2
}

/**
 * Builds and caches waveform peaks for the editor. The caller owns the cache
 * location; this object has no dependency on [EditorStore].
 */
object Waveform {

    const val DEFAULT_BUCKETS = 2_000

    private const val MAGIC = "SNDP"
    private const val VERSION = 1
    private const val HEADER_BYTES = 4 + 4 + 4 + 8
    private const val MAX_BUCKETS = 1_000_000
    private const val READ_FRAMES = 16 * 1024
    private const val TMP_SUFFIX = ".tmp"

    /**
     * Returns `2 * buckets` interleaved min/max samples normalized to `-1..1`.
     * The bucket count is capped by the sample count so no bucket is empty.
     * An empty input returns a flat envelope of zeros.
     */
    fun buildPeaks(samples: ShortArray, buckets: Int = DEFAULT_BUCKETS): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        if (samples.isEmpty()) return FloatArray(2 * buckets)
        val count = min(buckets, samples.size)
        val peaks = FloatArray(2 * count)
        for (bucket in 0 until count) {
            val start = (bucket.toLong() * samples.size / count).toInt()
            val end = ((bucket + 1).toLong() * samples.size / count).toInt()
            var low = samples[start] / 32768f
            var high = low
            for (index in start + 1 until end) {
                val value = samples[index] / 32768f
                if (value < low) low = value
                if (value > high) high = value
            }
            peaks[2 * bucket] = low
            peaks[2 * bucket + 1] = high
        }
        return peaks
    }

    /** Writes the cache atomically through a sibling `.tmp` file. */
    fun writeCache(file: File, peaks: FloatArray, durationMs: Long) {
        require(peaks.size % 2 == 0) { "Peaks must be min/max pairs" }
        val buckets = peaks.size / 2
        require(buckets in 1..MAX_BUCKETS) { "Invalid bucket count: $buckets" }
        val parent = file.absoluteFile.parentFile ?: file.absoluteFile
        parent.mkdirs()
        val tmp = File(parent, file.name + TMP_SUFFIX)
        try {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + peaks.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
            buffer.putInt(VERSION)
            buffer.putInt(buckets)
            buffer.putLong(durationMs)
            for (peak in peaks) buffer.putFloat(peak)
            FileOutputStream(tmp).use { out ->
                out.write(buffer.array())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    /** Reads a cache file, returning null when it is missing or malformed. */
    fun readCache(file: File): PeakData? {
        if (!file.isFile) return null
        val length = file.length()
        val maxLength = HEADER_BYTES + MAX_BUCKETS.toLong() * 8L
        if (length < HEADER_BYTES || length > maxLength) return null
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buffer.get(it) }
        if (String(magic, Charsets.US_ASCII) != MAGIC) return null
        if (buffer.int != VERSION) return null
        val buckets = buffer.int
        if (buckets !in 1..MAX_BUCKETS) return null
        val durationMs = buffer.long
        val expected = HEADER_BYTES + buckets.toLong() * 8L
        if (length != expected) return null
        val peaks = FloatArray(buckets * 2)
        for (index in peaks.indices) peaks[index] = buffer.float
        return PeakData(peaks, durationMs)
    }

    /**
     * Returns cached peaks when [peaksFile] is valid, otherwise decodes
     * [source] to 8 kHz mono through FFmpeg, streams the PCM into peaks, and
     * caches the result. Returns null when the source cannot be decoded.
     */
    fun decode(
        context: Context,
        source: File,
        peaksFile: File,
        durationMs: Long,
    ): PeakData? {
        runCatching { readCache(peaksFile) }.getOrNull()?.let { return it }
        val temp = File(context.cacheDir, "waveform-${UUID.randomUUID()}.wav")
        return try {
            Ffmpeg.run(
                "-y -i ${Ffmpeg.quote(source)} -ac 1 -ar 8000 " +
                    "-c:a pcm_s16le -f wav ${Ffmpeg.quote(temp)}"
            )
            val peaks = readPeaks(temp) ?: return null
            val data = PeakData(peaks, durationMs)
            runCatching { writeCache(peaksFile, peaks, durationMs) }
            data
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    /** Streams 16-bit PCM into peak buckets without holding the whole file. */
    private fun readPeaks(wav: File): FloatArray? {
        WavStreamReader(wav).use { reader ->
            val frames = reader.totalFrames
            if (frames <= 0L) return null
            val buckets = min(DEFAULT_BUCKETS.toLong(), frames).toInt()
            val peaks = FloatArray(2 * buckets)
            for (bucket in 0 until buckets) {
                peaks[2 * bucket] = Float.MAX_VALUE
                peaks[2 * bucket + 1] = -Float.MAX_VALUE
            }
            var bucket = 0
            var bucketEnd = boundary(1, frames, buckets)
            var position = 0L
            while (position < frames) {
                val samples = reader.read(position, READ_FRAMES)[0]
                if (samples.isEmpty()) break
                for (offset in samples.indices) {
                    val samplePosition = position + offset
                    while (samplePosition >= bucketEnd) {
                        bucket++
                        bucketEnd = boundary(bucket + 1L, frames, buckets)
                    }
                    val value = samples[offset]
                    if (value < peaks[2 * bucket]) peaks[2 * bucket] = value
                    if (value > peaks[2 * bucket + 1]) peaks[2 * bucket + 1] = value
                }
                position += samples.size
            }
            if (position < frames) return null
            return peaks
        }
    }

    private fun boundary(bucket: Long, frames: Long, buckets: Int): Long =
        bucket * frames / buckets
}

/**
 * Draws the min/max envelope with the playhead, the active selection and its
 * trim handles. Fractions are `0..1`; playback and trimming state stay with
 * the caller. Colors come from the Material theme.
 */
@Composable
fun WaveformView(
    peaks: FloatArray,
    playheadFraction: Float,
    selection: ClosedFloatingPointRange<Float>?,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                if (size.width > 0) {
                    seek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
        },
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val middle = height / 2f
        drawRect(color = colors.surfaceVariant)

        val start = selection?.start?.coerceIn(0f, 1f)
        val end = selection?.endInclusive?.coerceIn(0f, 1f)
        if (start != null && end != null && end > start) {
            drawRect(
                color = colors.primary.copy(alpha = 0.16f),
                topLeft = Offset(start * width, 0f),
                size = Size((end - start) * width, height),
            )
        }

        drawLine(
            color = colors.outline.copy(alpha = 0.4f),
            start = Offset(0f, middle),
            end = Offset(width, middle),
            strokeWidth = 1.dp.toPx(),
        )

        val bucketCount = peaks.size / 2
        if (bucketCount == 0) {
            drawLine(
                color = colors.outline,
                start = Offset(0f, middle),
                end = Offset(width, middle),
                strokeWidth = 1.dp.toPx(),
            )
        } else {
            val slot = width / bucketCount
            val envelope = Path()
            envelope.moveTo(0f, middle - peaks[1].coerceIn(-1f, 1f) * middle)
            for (bucket in 0 until bucketCount) {
                val right = if (bucket == bucketCount - 1) width else (bucket + 1) * slot
                val high = peaks[2 * bucket + 1].coerceIn(-1f, 1f)
                if (bucket > 0) envelope.lineTo(bucket * slot, middle - high * middle)
                envelope.lineTo(right, middle - high * middle)
            }
            for (bucket in bucketCount - 1 downTo 0) {
                val left = bucket * slot
                val right = if (bucket == bucketCount - 1) width else (bucket + 1) * slot
                val low = peaks[2 * bucket].coerceIn(-1f, 1f)
                envelope.lineTo(right, middle - low * middle)
                envelope.lineTo(left, middle - low * middle)
            }
            envelope.close()
            drawPath(path = envelope, color = colors.primary)
        }

        val playhead = playheadFraction.coerceIn(0f, 1f) * width
        drawLine(
            color = colors.onSurface,
            start = Offset(playhead, 0f),
            end = Offset(playhead, height),
            strokeWidth = 2.dp.toPx(),
        )

        if (start != null && end != null) {
            val handleWidth = 3.dp.toPx()
            drawLine(
                color = colors.onSurface,
                start = Offset(start * width, 0f),
                end = Offset(start * width, height),
                strokeWidth = handleWidth,
            )
            drawLine(
                color = colors.onSurface,
                start = Offset(end * width, 0f),
                end = Offset(end * width, height),
                strokeWidth = handleWidth,
            )
        }
    }
}
