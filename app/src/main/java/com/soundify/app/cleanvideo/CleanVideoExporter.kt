package com.soundify.app.cleanvideo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    fun default(codec: String?): VideoFormat = when (codec) {
        null -> VideoFormat.MKV
        in setOf("vp8", "vp9") -> VideoFormat.MKV
        else -> VideoFormat.MP4
    }

    /** Compatible formats with the best default first. */
    fun ordered(codec: String?): List<VideoFormat> {
        val first = default(codec)
        return listOf(first) + VideoFormat.entries.filter { it != first && supports(it, codec) }
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
        require(VideoCompatibility.supports(format, result.videoCodec)) {
            "${format.label} cannot hold ${result.videoCodec ?: "this"} video"
        }
        val source = File.createTempFile("soundify-source-", ".${sourceExtension(result.sourceName)}", context.cacheDir)
        val output = File.createTempFile("soundify-video-", ".${format.extension}", context.cacheDir)
        try {
            copyUriToFile(result.sourceUri, source)
            encodeVideo(source, stem, format, output)
            copyToUri(output, target)
        } finally {
            source.delete()
            output.delete()
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
            "-af \"volume=6dB,alimiter=limit=0.891:level=false\" "
        }

    private fun copyToUri(file: File, target: Uri) {
        val output = context.contentResolver.openOutputStream(target)
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
