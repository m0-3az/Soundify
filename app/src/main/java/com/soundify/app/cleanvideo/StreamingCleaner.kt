package com.soundify.app.cleanvideo

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

data class CleanVideoResult(
    val cleanedWav: File,
    val musicWav: File,
    val mp3: File,
    val m4a: File,
    val video: File?,
)

/**
 * Streaming 20-minute-capable pipeline:
 *   video -> 44.1k stereo float WAV -> 6 s chunks -> cleaned/music WAV
 *   -> loudness match -> MP3 + M4A + same-container video.
 *
 * The model runs at the source level. Stems are written with 6 dB of headroom
 * because the masks can reconstruct peaks above the input; the cleaned mix is
 * then matched to [TARGET_LUFS] / [TARGET_TP] so it is neither quiet nor
 * clipping.
 *
 * Only one chunk plus a 1 s overlap tail is held in RAM.
 */
class StreamingCleaner(private val context: Context) {

    suspend fun clean(
        uri: Uri,
        displayName: String?,
        onProgress: (Float, String) -> Unit,
    ): CleanVideoResult = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "cleanvideo").apply {
            deleteRecursively()
            mkdirs()
        }
        val outDir = File(context.filesDir, "exports/cleanvideo/${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        val inputExt = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            ?: "mp4"
        val input = File(work, "input.$inputExt")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            input.outputStream().use { stream.copyTo(it) }
        } ?: error("Could not open the selected video")

        onProgress(0.02f, "Extracting audio…")
        val audio = File(work, "audio.wav")
        runFfmpeg("-y -i ${q(input)} -vn -ac 2 -ar 44100 -c:a pcm_f32le ${q(audio)}")

        onProgress(0.04f, "Preparing on-device model…")
        val model = ensureModel(onProgress)

        val cleanedRaw = File(work, "cleaned_raw.wav")
        val cleanedWav = File(outDir, "cleaned.wav")
        val musicWav = File(outDir, "music.wav")
        val reader = WavStreamReader(audio)
        val cleanedWriter = WavStreamWriter(cleanedRaw, 2, 44100, gain = STEM_HEADROOM)
        val musicWriter = WavStreamWriter(musicWav, 2, 44100, gain = STEM_HEADROOM)
        val cleanedMerger = StreamMerger(OVERLAP)
        val musicMerger = StreamMerger(OVERLAP)

        try {
            ChunkSeparator(model.absolutePath).use { separator ->
                var start = 0L
                while (start < reader.totalFrames) {
                    val count = min(CHUNK.toLong(), reader.totalFrames - start).toInt()
                    val segment = reader.read(start, count)
                    val stereo = if (segment.size == 1) arrayOf(segment[0], segment[0].copyOf()) else segment
                    val stems = separator.separate(stereo)
                    val cleaned = Array(2) { ch ->
                        FloatArray(count) { i -> stems[1][ch][i] + stems[2][ch][i] }
                    }
                    val first = start == 0L
                    val last = start + count >= reader.totalFrames
                    cleanedMerger.accept(cleanedWriter, cleaned, first, last)
                    musicMerger.accept(musicWriter, stems[0], first, last)

                    start += HOP
                    val done = min(start, reader.totalFrames)
                    val fraction = if (reader.totalFrames > 0) done.toFloat() / reader.totalFrames else 1f
                    onProgress(
                        0.05f + 0.85f * fraction,
                        "Separating ${done / 44100}s / ${reader.totalFrames / 44100}s",
                    )
                }
            }
        } finally {
            cleanedWriter.close()
            musicWriter.close()
            reader.close()
        }

        onProgress(0.92f, "Matching loudness…")
        normalizeLoudness(cleanedRaw, cleanedWav)

        onProgress(0.94f, "Encoding MP3…")
        val mp3 = File(outDir, "cleaned.mp3")
        runFfmpeg("-y -i ${q(cleanedWav)} -c:a libmp3lame -b:a 192k ${q(mp3)}")

        onProgress(0.96f, "Encoding M4A…")
        val m4a = File(outDir, "cleaned.m4a")
        runFfmpeg("-y -i ${q(cleanedWav)} -c:a aac -b:a 192k ${q(m4a)}")

        onProgress(0.98f, "Muxing cleaned audio into the video…")
        val outExt = when (inputExt) {
            "mkv" -> "mkv"
            "webm" -> "webm"
            "mov" -> "mov"
            else -> "mp4"
        }
        val audioCodec = if (outExt == "webm") "libopus" else "aac"
        val video = File(outDir, "cleaned.$outExt")
        runFfmpeg(
            "-y -i ${q(input)} -i ${q(cleanedWav)} " +
                "-map 0:v:0 -map 1:a:0 -c:v copy -c:a $audioCodec -b:a 192k -shortest ${q(video)}"
        )

        work.deleteRecursively()
        onProgress(1f, "Done")
        CleanVideoResult(cleanedWav, musicWav, mp3, m4a, video)
    }

    private fun ensureModel(onProgress: (Float, String) -> Unit): File {
        val model = File(context.filesDir, "mrx_mask.onnx")
        if (model.exists() && model.length() > 100_000_000L) return model
        onProgress(0.03f, "Copying on-device model (first run)…")
        val tmp = File(context.filesDir, "mrx_mask.onnx.tmp")
        context.assets.open("mrx_mask.onnx").use { stream ->
            tmp.outputStream().use { stream.copyTo(it) }
        }
        if (!tmp.renameTo(model)) {
            tmp.copyTo(model, overwrite = true)
            tmp.delete()
        }
        return model
    }

    private fun runFfmpeg(command: String): String {
        val session = FFmpegKit.execute(command)
        val code = session.returnCode
        if (!ReturnCode.isSuccess(code)) {
            throw IllegalStateException("FFmpeg failed ($code). Check the selected file format.")
        }
        return session.output ?: ""
    }

    /**
     * Two-pass loudness match. The first pass measures the cleaned mix; the
     * second applies a linear gain with a true-peak limit. If the measurement
     * is unreadable (silence, truncated log), fall back to single-pass mode.
     */
    private fun normalizeLoudness(input: File, output: File) {
        val measure = runFfmpeg(
            "-y -i ${q(input)} " +
                "-af loudnorm=I=$TARGET_LUFS:TP=$TARGET_TP:LRA=11:print_format=json -f null -"
        )
        val stats = Loudness.parseLoudnormJson(measure)
        val filter = if (stats == null) {
            "loudnorm=I=$TARGET_LUFS:TP=$TARGET_TP:LRA=11"
        } else {
            "loudnorm=I=$TARGET_LUFS:TP=$TARGET_TP:LRA=11" +
                ":measured_I=${stats.inputI}" +
                ":measured_TP=${stats.inputTp}" +
                ":measured_LRA=${stats.inputLra}" +
                ":measured_thresh=${stats.inputThresh}" +
                ":offset=${stats.targetOffset}:linear=true"
        }
        runFfmpeg(
            "-y -i ${q(input)} -af \"$filter\" -ar 44100 -c:a pcm_s16le ${q(output)}"
        )
    }

    private fun q(file: File): String = "\"${file.absolutePath.replace("\"", "\\\"")}\""

    private class StreamMerger(private val overlap: Int) {
        private val fadeIn = FloatArray(overlap) { i ->
            if (overlap <= 1) 1f else (0.5 - 0.5 * cos(PI * i / (overlap - 1))).toFloat()
        }
        private val fadeOut = FloatArray(overlap) { 1f - fadeIn[it] }
        private var tail: Array<FloatArray>? = null

        fun accept(
            writer: WavStreamWriter,
            chunk: Array<FloatArray>,
            first: Boolean,
            last: Boolean,
        ) {
            val n = chunk[0].size
            if (first) {
                if (last) {
                    writer.writeAll(chunk)
                    tail = null
                } else {
                    val keep = min(overlap, n)
                    writer.write(chunk, 0, n - keep)
                    tail = copyRange(chunk, n - keep, keep)
                }
                return
            }
            val t = tail ?: error("missing overlap tail")
            val head = min(min(overlap, t[0].size), n)
            val mixed = Array(chunk.size) { ch ->
                FloatArray(head) { i -> t[ch][i] * fadeOut[i] + chunk[ch][i] * fadeIn[i] }
            }
            writer.writeAll(mixed)
            if (last) {
                writer.write(chunk, head, n - head)
                tail = null
            } else {
                val keep = min(overlap, n - head)
                writer.write(chunk, head, n - head - keep)
                tail = copyRange(chunk, n - keep, keep)
            }
        }

        private fun copyRange(src: Array<FloatArray>, offset: Int, count: Int): Array<FloatArray> =
            Array(src.size) { ch -> src[ch].copyOfRange(offset, offset + count) }
    }

    companion object {
        private const val CHUNK = 6 * 44100
        private const val OVERLAP = 44100
        private const val HOP = CHUNK - OVERLAP

        /** Intermediate stems use 6 dB of headroom before the 16-bit conversion. */
        private const val STEM_HEADROOM = 0.5f

        private const val TARGET_LUFS = -16.0
        private const val TARGET_TP = -1.5
    }
}
