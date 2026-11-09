package com.soundify.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns

data class AudioInfo(
    val displayName: String,
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)

object AudioMetadata {

    private val supportedMimes = setOf(
        "audio/raw",
        "audio/wav",
        "audio/x-wav",
        "audio/flac",
        "audio/x-flac",
        "audio/mpeg",
        "audio/mp4",
        "audio/m4a",
        "audio/mp4a-latm",
        "audio/aac",
        "audio/x-aac"
    )

    fun read(context: Context, uri: Uri): AudioInfo {
        val displayName = queryDisplayName(context, uri)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (mime == "audio/raw" && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT &&
                        encoding != AudioFormat.ENCODING_PCM_FLOAT
                    ) {
                        error("Only 16-bit WAV files are supported")
                    }
                }
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                return AudioInfo(
                    displayName = displayName,
                    mime = mime,
                    sampleRate = sampleRate,
                    channels = channels,
                    durationMs = durationUs / 1000L
                )
            }
            error("No audio track found in $displayName")
        } finally {
            extractor.release()
        }
    }

    fun isSupported(mime: String): Boolean =
        supportedMimes.contains(mime.lowercase()) || mime == "audio/raw"

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "stem"
    }
}
