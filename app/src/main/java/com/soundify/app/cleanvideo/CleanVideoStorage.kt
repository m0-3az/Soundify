package com.soundify.app.cleanvideo

import android.content.Context
import java.io.File

/**
 * Owns the timestamped stem directories under `files/exports/cleanvideo`.
 * Only directories created here are ever deleted; the source video and any
 * document the user saved through SAF are never touched.
 */
object CleanVideoStorage {

    fun exportRoot(context: Context): File = File(context.filesDir, "exports/cleanvideo")

    fun newExportDir(context: Context): File {
        val root = exportRoot(context).apply { mkdirs() }
        var dir = File(root, System.currentTimeMillis().toString())
        var suffix = 1
        while (!dir.mkdirs() && dir.exists()) {
            dir = File(root, "${System.currentTimeMillis()}-${suffix++}")
        }
        return dir
    }

    fun deleteResult(context: Context, result: CleanVideoResult?): Boolean {
        val dir = result?.clean?.file?.parentFile ?: return false
        return deleteExportDir(exportRoot(context), dir)
    }

    /** Deletes [dir] only when it is a direct child of [root]. */
    fun deleteExportDir(root: File, dir: File?): Boolean {
        if (dir == null) return false
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val dirPath = runCatching { dir.canonicalPath }.getOrNull() ?: return false
        if (dirPath == rootPath || !dirPath.startsWith(rootPath + File.separator)) return false
        return dir.deleteRecursively()
    }
}
