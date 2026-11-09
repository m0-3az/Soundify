package com.soundify.app.cleanvideo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

enum class CleanStage {
    PREPARING,
    SEPARATING,
    FINALIZING,
    DONE,
}

data class CleanProgress(
    val fraction: Float,
    val stage: CleanStage,
    val detail: String,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
    val etaSeconds: Long? = null,
)

enum class CleanStem(val label: String) {
    VOICE("Voice"),
    SFX("Sound effects"),
    MUSIC("Music"),
    CLEAN("Clean mix"),
}

/** Non-clean stems are stored this far below the model output; restored on save. */
internal const val STEM_RESTORE_DB = 6f

data class StemResult(
    val kind: CleanStem,
    val file: File,
    val durationMs: Long,
    val rmsDb: Float,
    val peakDb: Float,
) {
    /** Non-clean stems are stored 6 dB low; show the level they have on save. */
    val exportGainDb: Float
        get() = if (kind == CleanStem.CLEAN) 0f else STEM_RESTORE_DB

    val displayRmsDb: Float get() = rmsDb + exportGainDb
    val displayPeakDb: Float get() = peakDb + exportGainDb
}

data class CleanVideoResult(
    val voice: StemResult,
    val sfx: StemResult,
    val music: StemResult,
    val clean: StemResult,
    val sourceUri: Uri,
    val sourceName: String?,
    val videoCodec: String?,
    val videoDurationMs: Long,
    /** Set when loudness matching was skipped, e.g. a near-silent clean mix. */
    val cleanNote: String? = null,
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
 *   video -> 44.1k stereo float WAV -> model input gain -> 6 s chunks
 *   -> voice / SFX / music / clean WAV -> loudness match on the clean mix.
 *
 * The model's BatchNorm expects input normalized to -27 LUFS (the official
 * MRX reference implementation does the same). Each chunk is scaled to that
 * level before inference and every stem is divided by the same gain after,
 * so stems stay at the source level. Stems are written with 6 dB of headroom
 * because the masks can reconstruct peaks above the input; the clean mix is
 * then matched to -16 LUFS / -1.5 dBTP so it is neither quiet nor clipping.
 * Encoded MP3/M4A and the muxed video are created on Save, not here.
 *
 * Only one chunk plus a 1 s overlap tail is held in RAM.
 */
class StreamingCleaner(private val context: Context) {

    suspend fun clean(
        uri: Uri,
        displayName: String?,
        onProgress: (CleanProgress) -> Unit,
    ): CleanVideoResult = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "cleanvideo").apply {
            deleteRecursively()
            mkdirs()
        }
        val outDir = CleanVideoStorage.newExportDir(context)
        var completed = false
        try {
            val inputExt = displayName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                ?: "mp4"
            val input = File(work, "input.$inputExt")
            onProgress(CleanProgress(0.01f, CleanStage.PREPARING, "Copying the video…"))
            context.contentResolver.openInputStream(uri)?.use { stream ->
                input.outputStream().use { stream.copyTo(it) }
            } ?: error("Could not open the selected video")

            val probe = probeVideo(input)
            if (!probe.hasAudio) {
                throw IllegalStateException("This video has no audio track.")
            }

            val audio = File(work, "audio.wav")
            onProgress(CleanProgress(0.02f, CleanStage.PREPARING, "Extracting audio…"))
            Ffmpeg.run(
                "-y -i ${Ffmpeg.quote(input)} -vn -ac 2 -ar 44100 -c:a pcm_f32le ${Ffmpeg.quote(audio)}"
            )

            val modelFile = File(context.filesDir, "mrx_mask.onnx")
            if (!modelFile.exists() || modelFile.length() <= 100_000_000L) {
                onProgress(
                    CleanProgress(0.03f, CleanStage.PREPARING, "Copying the on-device model (first run)…")
                )
            }
            val model = ensureModel()
            currentCoroutineContext().ensureActive()

            val cleanRaw = File(work, "clean_raw.wav")
            val voiceWav = File(outDir, "voice.wav")
            val sfxWav = File(outDir, "sfx.wav")
            val musicWav = File(outDir, "music.wav")
            val cleanWav = File(outDir, "clean.wav")
            val reader = WavStreamReader(audio)
            val durationMs = reader.totalFrames * 1000L / 44100L
            val totalFrames = reader.totalFrames
            val inputGain = InferenceGain.gainFor(Loudness.measure(audio)?.inputI)
            val restoreGain = 1f / inputGain
            val ranges = chunkRanges(totalFrames, CHUNK, HOP)
            val chunkCount = ranges.size.coerceAtLeast(1)
            val separationStarted = SystemClock.elapsedRealtime()
            val voiceWriter = WavStreamWriter(voiceWav, 2, 44100, gain = STEM_HEADROOM)
            val sfxWriter = WavStreamWriter(sfxWav, 2, 44100, gain = STEM_HEADROOM)
            val musicWriter = WavStreamWriter(musicWav, 2, 44100, gain = STEM_HEADROOM)
            val cleanWriter = WavStreamWriter(cleanRaw, 2, 44100, gain = STEM_HEADROOM)
            val voiceMerger = StreamMerger(OVERLAP)
            val sfxMerger = StreamMerger(OVERLAP)
            val musicMerger = StreamMerger(OVERLAP)
            val cleanMerger = StreamMerger(OVERLAP)

            val workerTid = android.os.Process.myTid()
            val previousPriority = android.os.Process.getThreadPriority(workerTid)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                ChunkSeparator(model.absolutePath).use { separator ->
                    for ((index, range) in ranges.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val start = range.start
                        val count = range.count
                        val segment = reader.read(start, count)
                        val stereo = if (segment.size == 1) arrayOf(segment[0], segment[0].copyOf()) else segment
                        InferenceGain.scaleInPlace(stereo, inputGain)
                        val stems = separator.separate(stereo)
                        ResidualMixer.apply(stereo, stems, RESIDUAL_MODE)
                        for (stem in stems) {
                            InferenceGain.scaleInPlace(stem, restoreGain)
                        }
                        val music = stems[0]
                        val voice = stems[1]
                        val sfx = stems[2]
                        val clean = Array(2) { ch ->
                            FloatArray(count) { i -> voice[ch][i] + sfx[ch][i] }
                        }
                        voiceMerger.accept(voiceWriter, voice, range.isFirst, range.isLast)
                        sfxMerger.accept(sfxWriter, sfx, range.isFirst, range.isLast)
                        musicMerger.accept(musicWriter, music, range.isFirst, range.isLast)
                        cleanMerger.accept(cleanWriter, clean, range.isFirst, range.isLast)

                        val processed = min(start + count, totalFrames)
                        val chunkIndex = index + 1
                        val fraction = if (totalFrames > 0) processed.toFloat() / totalFrames else 1f
                        val elapsedMs = SystemClock.elapsedRealtime() - separationStarted
                        val eta = if (fraction > 0.01f && elapsedMs > 0L) {
                            (elapsedMs * (1.0 - fraction) / fraction / 1000.0).toLong()
                                .coerceAtLeast(1L)
                        } else {
                            null
                        }
                        onProgress(
                            CleanProgress(
                                fraction = 0.05f + 0.85f * fraction,
                                stage = CleanStage.SEPARATING,
                                detail = "Chunk $chunkIndex of $chunkCount · " +
                                    "${formatClock(processed / 44100)} / " +
                                    "${formatClock(totalFrames / 44100)}",
                                chunkIndex = chunkIndex,
                                chunkCount = chunkCount,
                                etaSeconds = eta,
                            )
                        )
                    }
                }
            } finally {
                android.os.Process.setThreadPriority(previousPriority)
                voiceWriter.close()
                sfxWriter.close()
                musicWriter.close()
                cleanWriter.close()
                reader.close()
            }

            currentCoroutineContext().ensureActive()
            onProgress(CleanProgress(0.92f, CleanStage.FINALIZING, "Matching loudness…"))
            val normalization = CleanMixNormalizer().normalize(cleanRaw, cleanWav)
            if (!normalization.applied) {
                onProgress(CleanProgress(0.94f, CleanStage.FINALIZING, normalization.detail))
            }

            currentCoroutineContext().ensureActive()
            onProgress(CleanProgress(0.96f, CleanStage.FINALIZING, "Measuring the four stems…"))
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
                videoCodec = probe.videoCodec,
                videoDurationMs = probe.durationMs.takeIf { it > 0L } ?: durationMs,
                cleanNote = normalization.detail.takeIf { !normalization.applied },
            )

            onProgress(CleanProgress(1f, CleanStage.DONE, "Done"))
            completed = true
            result
        } finally {
            // A cancelled or failed run must not leave partial stems behind.
            // Saved documents (SAF targets) and the source video are untouched.
            if (!completed) {
                CleanVideoStorage.deleteExportDir(CleanVideoStorage.exportRoot(context), outDir)
            }
            work.deleteRecursively()
        }
    }

    private fun formatClock(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private data class VideoProbe(
        val videoCodec: String?,
        val hasAudio: Boolean,
        val durationMs: Long,
    )

    private fun probeVideo(file: File): VideoProbe {
        val info = runCatching {
            FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation
        }.getOrNull() ?: return VideoProbe(null, hasAudio = true, durationMs = 0L)
        val video = info.streams?.firstOrNull { it.type == "video" }
        val hasAudio = info.streams?.any { it.type == "audio" } ?: true
        val durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000.0).toLong() } ?: 0L
        return VideoProbe(video?.codec, hasAudio, durationMs)
    }

    private fun ensureModel(): File {
        val model = File(context.filesDir, "mrx_mask.onnx")
        if (model.exists() && model.length() > 100_000_000L) return model
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
        /** Off until the bake-off shows residual assignment helps on real clips. */
        private val RESIDUAL_MODE = ResidualMode.PASS
    }
}
