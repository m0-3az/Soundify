package com.soundify.app.cleanvideo

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CleanVideoExporter"

enum class AudioFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    WAV("WAV (lossless)", "wav", "audio/wav"),
    FLAC("FLAC (lossless)", "flac", "audio/flac"),
    MP3("MP3 192 kbps", "mp3", "audio/mpeg"),
    M4A("M4A AAC 192 kbps", "m4a", "audio/mp4"),
}

enum class VideoFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    MP4("MP4", "mp4", "video/mp4"),
    MKV("MKV", "mkv", "video/x-matroska"),
    WEBM("WebM", "webm", "video/webm"),
}

object VideoCompatibility {
    private val mp4Codecs = setOf("h264", "hevc", "av1", "mpeg4")
    private val webmCodecs = setOf("vp8", "vp9", "av1")

    fun supports(format: VideoFormat, codec: String?): Boolean = when (format) {
        VideoFormat.MP4 -> codec == null || codec in mp4Codecs
        VideoFormat.MKV -> true
        VideoFormat.WEBM -> codec in webmCodecs
    }

    /**
     * MP4 only when the source codec is actually legal in it. Everything else
     * (including unknown codecs) goes to Matroska, which can hold any stream.
     */
    fun default(codec: String?): VideoFormat =
        if (codec != null && supports(VideoFormat.MP4, codec)) VideoFormat.MP4 else VideoFormat.MKV

    /** Compatible formats with the best default first; unsupported ones are omitted. */
    fun ordered(codec: String?): List<VideoFormat> {
        val first = default(codec)
        return listOf(first) + VideoFormat.entries.filter {
            it != first && supports(it, codec)
        }
    }
}

class CleanVideoExporter(private val context: Context) {

    suspend fun exportAudio(
        stem: StemResult,
        format: AudioFormat,
        target: Uri,
    ) = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("soundify-audio-", ".${format.extension}", context.cacheDir)
        try {
            encodeAudio(stem, format, temp)
            copyToUri(temp, target)
        } finally {
            temp.delete()
        }
    }

    fun encodeAudio(stem: StemResult, format: AudioFormat, output: File) {
        val codec = when (format) {
            AudioFormat.WAV -> "pcm_s16le"
            AudioFormat.FLAC -> "flac"
            AudioFormat.MP3 -> "libmp3lame"
            AudioFormat.M4A -> "aac"
        }
        val bitrate = when (format) {
            AudioFormat.MP3, AudioFormat.M4A -> "-b:a 192k "
            else -> ""
        }
        val filter = stemFilter(stem)
        Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(stem.file)} $filter-ar 44100 -c:a $codec $bitrate${Ffmpeg.quote(output)}"
        )
    }

    suspend fun exportVideo(
        result: CleanVideoResult,
        stem: StemResult,
        format: VideoFormat,
        target: Uri,
    ) = withContext(Dispatchers.IO) {
        require(result.hasVideo) { "This file has no video track." }
        require(VideoCompatibility.supports(format, result.videoCodec)) {
            "${format.label} cannot hold ${result.videoCodec ?: "this"} video"
        }
        val output = File.createTempFile("soundify-video-", ".${format.extension}", context.cacheDir)
        try {
            encodeVideoFromSource(result, stem, format, output)
            copyToUri(output, target)
        } finally {
            output.delete()
        }
    }

    /**
     * Feed FFmpeg the open source descriptor instead of copying the whole
     * video into the cache a second time. Some document providers hand out a
     * stream that FFmpeg cannot seek, so fall back to one cached copy if the
     * descriptor path fails.
     */
    private fun encodeVideoFromSource(
        result: CleanVideoResult,
        stem: StemResult,
        format: VideoFormat,
        output: File,
    ) {
        val viaDescriptor = runCatching {
            context.contentResolver.openFileDescriptor(result.sourceUri, "r")?.use { descriptor ->
                val source = File("/proc/self/fd/${descriptor.fd}")
                check(source.exists()) { "The source file descriptor is not readable" }
                encodeVideo(source, stem, format, output)
            } ?: error("Could not reopen the source video")
        }
        if (viaDescriptor.isSuccess) return

        android.util.Log.w(
            TAG,
            "Muxing from the source descriptor failed; using one cached copy",
            viaDescriptor.exceptionOrNull(),
        )
        val source = File.createTempFile(
            "soundify-source-",
            ".${sourceExtension(result.sourceName)}",
            context.cacheDir,
        )
        try {
            copyUriToFile(result.sourceUri, source)
            encodeVideo(source, stem, format, output)
        } finally {
            source.delete()
        }
    }

    fun encodeVideo(source: File, stem: StemResult, format: VideoFormat, output: File) {
        val audioCodec = if (format == VideoFormat.WEBM) "libopus" else "aac"
        val filter = stemFilter(stem)
        Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(source)} -i ${Ffmpeg.quote(stem.file)} " +
                "-map 0:v:0 -map 1:a:0 -map_metadata 0 -c:v copy -c:a $audioCodec " +
                "-b:a 192k $filter${Ffmpeg.quote(output)}"
        )
    }

    /**
     * Stems are stored 6 dB below the model output so 16-bit PCM cannot clip.
     * Restore that gain on export and catch any peaks with a -1 dBFS limiter.
     */
    private fun stemFilter(stem: StemResult): String =
        if (stem.kind == CleanStem.CLEAN) {
            ""
        } else {
            "-af \"volume=${STEM_RESTORE_DB.toInt()}dB,alimiter=limit=0.891:level=false\" "
        }

    private fun copyToUri(file: File, target: Uri) {
        // "wt" truncates an existing document on API 30+ so saving over a
        // larger previous file cannot leave stale bytes at the end.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "wt" else "w"
        val output = runCatching {
            context.contentResolver.openOutputStream(target, mode)
        }.getOrNull() ?: context.contentResolver.openOutputStream(target)
        ?: error("Could not open the save location")
        output.use { sink ->
            file.inputStream().use { it.copyTo(sink) }
        }
    }

    private fun copyUriToFile(uri: Uri, file: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not reopen the source video")
        input.use { source ->
            file.outputStream().use { source.copyTo(it) }
        }
    }

    private fun sourceExtension(name: String?): String =
        name
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            ?: "mp4"
}
