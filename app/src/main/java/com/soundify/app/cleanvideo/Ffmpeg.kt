package com.soundify.app.cleanvideo

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object Ffmpeg {
    fun run(command: String): String {
        val session = FFmpegKit.execute(command)
        val code = session.returnCode
        if (!ReturnCode.isSuccess(code)) {
            throw IllegalStateException("FFmpeg failed ($code). Check the selected file format.")
        }
        return session.output ?: ""
    }

    fun quote(file: File): String = "\"${file.absolutePath.replace("\"", "\\\"")}\""
}
