package com.soundify.app.cleanvideo

data class MediaStreamInfo(
    val type: String?,
    val codec: String?,
    val attachedPic: Boolean,
)

data class MediaProbe(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val videoCodec: String?,
    val durationMs: Long,
) {
    companion object {
        /**
         * A video stream counts only when it is not cover art. An audio file
         * with an embedded JPEG must behave as audio-only.
         */
        fun classify(streams: List<MediaStreamInfo>, durationMs: Long): MediaProbe {
            val video = streams.firstOrNull { it.type == "video" && !it.attachedPic }
            return MediaProbe(
                hasVideo = video != null,
                hasAudio = streams.any { it.type == "audio" },
                videoCodec = video?.codec,
                durationMs = durationMs,
            )
        }
    }
}
