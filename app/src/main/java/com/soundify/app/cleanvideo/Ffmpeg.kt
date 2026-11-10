package com.soundify.app.cleanvideo

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import java.io.File

object Ffmpeg {
    fun run(command: String): String {
        val session = FFmpegKit.execute(command)
        val code = session.returnCode
        val log = session.output ?: ""
        if (!ReturnCode.isSuccess(code)) {
            throw IllegalStateException(friendlyMessage(code.toString(), log))
        }
        return log
    }

    /**
     * Non-blocking variant used by the editor export. Progress arrives through
     * [onStatistics] on FFmpeg's own callback thread. [onComplete] receives the
     * success flag and the complete log on success, or a friendly failure
     * message that retains the FFmpeg return code.
     */
    fun runAsync(
        command: String,
        onStatistics: (Statistics) -> Unit,
        onComplete: (Boolean, String) -> Unit,
    ): FFmpegSession = FFmpegKit.executeAsync(
        command,
        { session ->
            val code = session.returnCode
            val log = session.output ?: ""
            val success = ReturnCode.isSuccess(code)
            onComplete(success, if (success) log else friendlyMessage(code.toString(), log))
        },
        { _ -> },
        { statistics -> onStatistics(statistics) },
    )

    fun quote(file: File): String = "\"${file.absolutePath.replace("\"", "\\\"")}\""

    internal fun friendlyMessage(code: String, log: String): String {
        val lower = log.lowercase()
        val reason = when {
            "no space left" in lower ->
                "Not enough storage to finish. Free some space and try again."

            "received signal 2" in lower || "cancelled" in lower || "canceled" in lower ->
                "Export was cancelled."

            "does not contain any stream" in lower || "matches no streams" in lower ||
                "no audio" in lower ->
                "This file has no readable audio track."

            "no such file or directory" in lower ->
                "A file needed for this step is missing. Choose the video again and retry."

            "invalid data found" in lower || "could not find codec" in lower ->
                "This file uses an audio or video format Soundify cannot read."

            "unknown encoder" in lower || "encoder not found" in lower ->
                "This device does not have the requested encoder."

            else -> if (code.isBlank()) "FFmpeg failed." else "FFmpeg failed ($code)."
        }
        return "$reason ${tail(log)}"
    }

    /** Keep the last lines of the FFmpeg log, which carry the actual reason. */
    private fun tail(log: String, maxChars: Int = 400): String {
        val trimmed = log.trim()
        if (trimmed.isEmpty()) return "No details were reported."
        val start = (trimmed.length - maxChars).coerceAtLeast(0)
        return "…" + trimmed.substring(start).trim()
    }
}
