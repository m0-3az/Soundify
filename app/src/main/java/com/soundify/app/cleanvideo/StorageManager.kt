package com.soundify.app.cleanvideo

import android.content.Context
import java.io.File

/** Byte breakdown for the in-app storage screen. */
data class StorageUsage(
    val projectsBytes: Long,
    val cleanVideoBytes: Long,
    val otherExportsBytes: Long,
    val cacheBytes: Long,
    val modelBytes: Long,
    val appBytes: Long = 0L,
) {
    val userDataBytes: Long
        get() = projectsBytes + cleanVideoBytes + otherExportsBytes + cacheBytes

    val totalBytes: Long
        get() = userDataBytes + appBytes + modelBytes

    companion object {
        fun of(
            projectsRoot: File,
            exportsRoot: File,
            cacheRoot: File,
            modelFile: File,
            appBytes: Long = 0L,
        ): StorageUsage {
            val cleanVideoRoot = File(exportsRoot, "cleanvideo")
            val cleanVideoBytes = sizeOf(cleanVideoRoot)
            val exportsBytes = sizeOf(exportsRoot)
            return StorageUsage(
                projectsBytes = sizeOf(projectsRoot),
                cleanVideoBytes = cleanVideoBytes,
                otherExportsBytes = (exportsBytes - cleanVideoBytes).coerceAtLeast(0L),
                cacheBytes = sizeOf(cacheRoot),
                modelBytes = if (modelFile.isFile) modelFile.length() else 0L,
                appBytes = appBytes,
            )
        }

        fun sizeOf(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        }
    }
}

/** Pure filesystem operations behind [StorageManager], kept testable off-device. */
object StorageCleanup {

    /** Deletes the app cache (temporary files) and returns the bytes freed. */
    fun cleanTemporary(cacheRoot: File): Long {
        val before = StorageUsage.sizeOf(cacheRoot)
        cacheRoot.listFiles()?.forEach { child ->
            val parent = child.canonicalFile.parentFile
            if (parent == cacheRoot.canonicalFile) {
                child.deleteRecursively()
            }
        }
        return before - StorageUsage.sizeOf(cacheRoot)
    }

    /** Deletes every Clean Video stem directory and returns the bytes freed. */
    fun deleteCleanVideoResults(cleanVideoRoot: File): Long {
        fun runBytes(): Long =
            cleanVideoRoot.listFiles { file -> file.isDirectory }
                ?.sumOf { StorageUsage.sizeOf(it) }
                ?: 0L

        val before = runBytes()
        CleanVideoHistoryStore(cleanVideoRoot).deleteAllRuns()
        return before - runBytes()
    }
}

class StorageManager(private val context: Context) {
    private val filesDir: File = context.filesDir
    private val exportsRoot = File(filesDir, "exports")
    private val projectsRoot = File(filesDir, "projects")
    private val cacheRoot: File = context.cacheDir
    private val modelFile = File(filesDir, "mrx_mask.onnx")

    fun usage(): StorageUsage =
        StorageUsage.of(
            projectsRoot = projectsRoot,
            exportsRoot = exportsRoot,
            cacheRoot = cacheRoot,
            modelFile = modelFile,
            appBytes = installedAppBytes(context),
        )

    fun cleanTemporary(): Long = StorageCleanup.cleanTemporary(cacheRoot)

    fun deleteAllCleanVideo(): Long =
        StorageCleanup.deleteCleanVideoResults(File(exportsRoot, "cleanvideo"))

    private fun installedAppBytes(context: Context): Long {
        val info = context.applicationInfo
        val paths = buildList {
            info.sourceDir?.let { add(it) }
            info.splitSourceDirs?.let { addAll(it) }
        }
        return paths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
    }
}
