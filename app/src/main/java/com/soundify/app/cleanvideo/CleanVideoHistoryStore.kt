package com.soundify.app.cleanvideo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class CleanVideoHistoryStem(
    val kind: String,
    val fileName: String,
    val durationMs: Long,
    val rmsDb: Float,
    val peakDb: Float,
)

@Serializable
data class CleanVideoHistoryEntry(
    val id: String,
    val fingerprint: String,
    val displayName: String,
    val createdAt: Long,
    val durationMs: Long,
    val videoCodec: String? = null,
    val cleanNote: String? = null,
    val stems: List<CleanVideoHistoryStem>,
    val totalBytes: Long,
    val sourceUri: String? = null,
    val appVersion: String = "",
)

/**
 * Persistent Clean Video history. Entries live in `history.json` next to the
 * stem directories under `files/exports/cleanvideo`; the directory name is
 * the entry id. Deleting an entry removes its stems but never the root or
 * files the user saved through SAF.
 */
class CleanVideoHistoryStore(private val root: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val historyFile: File
        get() = File(root, "history.json").apply { parentFile?.mkdirs() }

    fun list(): List<CleanVideoHistoryEntry> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<CleanVideoHistoryEntry>>(historyFile.readText())
        }.getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }

    fun find(fingerprint: String): CleanVideoHistoryEntry? =
        list().firstOrNull { it.fingerprint == fingerprint }

    fun save(entry: CleanVideoHistoryEntry) {
        val entries = list().filterNot { it.id == entry.id } + entry
        write(entries.sortedByDescending { it.createdAt })
    }

    /** Removes the entry and its stem directory. Returns false when the id escapes [root]. */
    fun delete(id: String): Boolean {
        val dir = childDir(id) ?: return false
        val entries = list()
        val remaining = entries.filterNot { it.id == id }
        val removed = remaining.size != entries.size
        val deleted = CleanVideoStorage.deleteExportDir(root, dir)
        if (removed || deleted) {
            write(remaining)
        }
        return removed || deleted
    }

    /** Deletes every stem directory under the root and empties the history. */
    fun deleteAllRuns(): Boolean {
        root.mkdirs()
        val dirs = root.listFiles { file -> file.isDirectory } ?: emptyArray()
        var deleted = false
        for (dir in dirs) {
            if (CleanVideoStorage.deleteExportDir(root, dir)) deleted = true
        }
        write(emptyList())
        return deleted
    }

    fun totalBytes(): Long {
        fun sizeOf(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        }
        return sizeOf(root)
    }

    private fun write(entries: List<CleanVideoHistoryEntry>) {
        val tmp = File(root, "history.json.tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(historyFile)) {
            tmp.copyTo(historyFile, overwrite = true)
            tmp.delete()
        }
    }

    /** Resolves an id to a direct child directory, refusing path traversal. */
    private fun childDir(id: String): File? {
        if (id.isBlank()) return null
        val dir = File(root, id)
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val dirPath = runCatching { dir.canonicalPath }.getOrNull() ?: return null
        if (dirPath == rootPath || !dirPath.startsWith(rootPath + File.separator)) return null
        return dir
    }

    companion object {
        /** Builds the persisted entry for a finished run. */
        fun entryFor(
            result: CleanVideoResult,
            fingerprint: String,
            appVersion: String,
        ): CleanVideoHistoryEntry {
            val id = result.clean.file.parentFile?.name ?: UUID.randomUUID().toString()
            return CleanVideoHistoryEntry(
                id = id,
                fingerprint = fingerprint,
                displayName = result.sourceName ?: "Clean Video",
                createdAt = System.currentTimeMillis(),
                durationMs = result.videoDurationMs,
                videoCodec = result.videoCodec,
                cleanNote = result.cleanNote,
                stems = result.stems.map { stem ->
                    CleanVideoHistoryStem(
                        kind = stem.kind.name,
                        fileName = stem.file.name,
                        durationMs = stem.durationMs,
                        rmsDb = stem.rmsDb,
                        peakDb = stem.peakDb,
                    )
                },
                totalBytes = result.stems.sumOf { it.file.length() },
                sourceUri = result.sourceUri.toString(),
                appVersion = appVersion,
            )
        }
    }
}

/**
 * Stable identity for a source video. Hashing only the edges keeps selection
 * fast on multi-hundred-megabyte files while still distinguishing re-encodes
 * and trims.
 */
object VideoFingerprint {
    private const val EDGE_BYTES = 256 * 1024

    fun of(size: Long, head: ByteArray, tail: ByteArray, durationMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(longBytes(size))
        digest.update(head)
        digest.update(tail)
        digest.update(longBytes(durationMs))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Returns null when the source cannot be read; callers should then skip dedup. */
    fun read(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        } ?: -1L
        if (size <= 0L) return@runCatching null
        val edge = EDGE_BYTES.toLong().coerceAtMost(size).toInt()
        val head = ByteArray(edge)
        val tail = ByteArray(edge)
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                input.readFully(head)
                input.channel.position((size - edge).coerceAtLeast(0L))
                input.readFully(tail)
            }
        } ?: return@runCatching null
        return@runCatching of(size, head, tail, 0L)
    }.getOrNull()

    private fun longBytes(value: Long): ByteArray =
        ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }

    private fun FileInputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read <= 0) break
            offset += read
        }
    }
}
