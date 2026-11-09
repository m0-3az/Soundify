package com.soundify.app.cleanvideo

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

enum class CleanStem(val label: String) {
    VOICE("Voice"),
    SFX("Sound effects"),
    MUSIC("Music"),
    CLEAN("Clean mix"),
}

data class StemResult(
    val kind: CleanStem,
    val file: File,
    val durationMs: Long,
    val rmsDb: Float,
    val peakDb: Float,
)

data class CleanVideoResult(
    val voice: StemResult,
    val sfx: StemResult,
    val music: StemResult,
    val clean: StemResult,
    val sourceUri: Uri,
    val sourceName: String?,
    val videoCodec: String?,
    val videoDurationMs: Long,
) {
    fun stem(kind: CleanStem): StemResult = when (kind) {
        CleanStem.VOICE -> voice
        CleanStem.SFX -> sfx
        CleanStem.MUSIC -> music
        CleanStem.CLEAN -> clean
    }

    val stems: List<StemResult> get() = listOf(voice, sfx, music, clean)
    val cleanedWav: File get() = clean.file
    val musicWav: File get() = music.file
}

/**
 * Streaming 20-minute-capable pipeline:
 *   video -> 44.1k stereo float WAV -> 6 s chunks -> voice / SFX / music / clean WAV
 *   -> loudness match on the clean mix.
 *
 * The model runs at the source level. Stems are written with 6 dB of headroom
 * because the masks can reconstruct peaks above the input; the clean mix is
 * then matched to [TARGET_LUFS] / [TARGET_TP] so it is neither quiet nor
 * clipping. Encoded MP3/M4A and the muxed video are created on Save, not here.
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

        val (videoCodec, probedDurationMs) = probeVideo(input)

        onProgress(0.02f, "Extracting audio…")
        val audio = File(work, "audio.wav")
        Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(input)} -vn -ac 2 -ar 44100 -c:a pcm_f32le ${Ffmpeg.quote(audio)}"
        )

        onProgress(0.04f, "Preparing on-device model…")
        val model = ensureModel(onProgress)
        currentCoroutineContext().ensureActive()

        val cleanRaw = File(work, "clean_raw.wav")
        val voiceWav = File(outDir, "voice.wav")
        val sfxWav = File(outDir, "sfx.wav")
        val musicWav = File(outDir, "music.wav")
        val cleanWav = File(outDir, "clean.wav")
        val reader = WavStreamReader(audio)
        val durationMs = reader.totalFrames * 1000L / 44100L
        val voiceWriter = WavStreamWriter(voiceWav, 2, 44100, gain = STEM_HEADROOM)
        val sfxWriter = WavStreamWriter(sfxWav, 2, 44100, gain = STEM_HEADROOM)
        val musicWriter = WavStreamWriter(musicWav, 2, 44100, gain = STEM_HEADROOM)
        val cleanWriter = WavStreamWriter(cleanRaw, 2, 44100, gain = STEM_HEADROOM)
        val voiceMerger = StreamMerger(OVERLAP)
        val sfxMerger = StreamMerger(OVERLAP)
        val musicMerger = StreamMerger(OVERLAP)
        val cleanMerger = StreamMerger(OVERLAP)

        try {
            ChunkSeparator(model.absolutePath).use { separator ->
                var start = 0L
                while (start < reader.totalFrames) {
                    currentCoroutineContext().ensureActive()
                    val count = min(CHUNK.toLong(), reader.totalFrames - start).toInt()
                    val segment = reader.read(start, count)
                    val stereo = if (segment.size == 1) arrayOf(segment[0], segment[0].copyOf()) else segment
                    val stems = separator.separate(stereo)
                    val music = stems[0]
                    val voice = stems[1]
                    val sfx = stems[2]
                    val clean = Array(2) { ch ->
                        FloatArray(count) { i -> voice[ch][i] + sfx[ch][i] }
                    }
                    val first = start == 0L
                    val last = start + count >= reader.totalFrames
                    voiceMerger.accept(voiceWriter, voice, first, last)
                    sfxMerger.accept(sfxWriter, sfx, first, last)
                    musicMerger.accept(musicWriter, music, first, last)
                    cleanMerger.accept(cleanWriter, clean, first, last)

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
            voiceWriter.close()
            sfxWriter.close()
            musicWriter.close()
            cleanWriter.close()
            reader.close()
        }

        currentCoroutineContext().ensureActive()
        onProgress(0.92f, "Matching loudness…")
        normalizeLoudness(cleanRaw, cleanWav)

        currentCoroutineContext().ensureActive()
        onProgress(0.96f, "Analyzing stems…")
        val voiceStats = voiceWriter.stats()
        val sfxStats = sfxWriter.stats()
        val musicStats = musicWriter.stats()
        val cleanStats = Wav.analyze(cleanWav)
        val result = CleanVideoResult(
            voice = StemResult(CleanStem.VOICE, voiceWav, durationMs, voiceStats.rmsDb, voiceStats.peakDb),
            sfx = StemResult(CleanStem.SFX, sfxWav, durationMs, sfxStats.rmsDb, sfxStats.peakDb),
            music = StemResult(CleanStem.MUSIC, musicWav, durationMs, musicStats.rmsDb, musicStats.peakDb),
            clean = StemResult(CleanStem.CLEAN, cleanWav, durationMs, cleanStats.rmsDb, cleanStats.peakDb),
            sourceUri = uri,
            sourceName = displayName,
            videoCodec = videoCodec,
            videoDurationMs = probedDurationMs.takeIf { it > 0L } ?: durationMs,
        )

        work.deleteRecursively()
        onProgress(1f, "Done")
        result
    }

    private fun probeVideo(file: File): Pair<String?, Long> {
        val info = runCatching {
            FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation
        }.getOrNull() ?: return null to 0L
        val video = info.streams?.firstOrNull { it.type == "video" }
        val durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000.0).toLong() } ?: 0L
        return video?.codec to durationMs
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

    /**
     * Two-pass loudness match. The first pass measures the cleaned mix; the
     * second applies a linear gain with a true-peak limit. If the measurement
     * is unreadable (silence, truncated log), fall back to single-pass mode.
     */
    private fun normalizeLoudness(input: File, output: File) {
        val measure = Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(input)} " +
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
        Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(input)} -af \"$filter\" -ar 44100 -c:a pcm_s16le ${Ffmpeg.quote(output)}"
        )
    }

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
