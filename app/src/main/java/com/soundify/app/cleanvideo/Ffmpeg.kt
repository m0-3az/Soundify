package com.soundify.app.cleanvideo

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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

    fun quote(file: File): String = "\"${file.absolutePath.replace("\"", "\\\"")}\""

    private fun friendlyMessage(code: String, log: String): String {
        val lower = log.lowercase()
        val reason = when {
            "no space left" in lower ->
                "Not enough storage to finish. Free some space and try again."

            "no such file or directory" in lower ->
                "A file needed for this step is missing. Choose the video again and retry."

            "invalid data found" in lower || "could not find codec" in lower ->
                "This file uses an audio or video format Soundify cannot read."

            "unknown encoder" in lower || "encoder not found" in lower ->
                "This device does not have the requested encoder."

            else -> "FFmpeg failed ($code)."
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
