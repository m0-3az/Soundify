package com.soundify.app.editor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

data class EditorSummary(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val durationMs: Long,
    val bytes: Long,
)

data class RegisteredSource(
    val file: File,
    val created: Boolean,
)

/**
 * Persists editor projects under [root]. Every project owns its `sources/`
 * and `peaks/` directories.
 */
class EditorStore(private val root: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun list(): List<EditorSummary> {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val project = load(dir.name) ?: return@mapNotNull null
            EditorSummary(
                id = dir.name,
                name = project.name,
                updatedAt = project.updatedAt,
                durationMs = project.durationMs,
                bytes = bytesOf(dir.name),
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): EditorProject? {
        val dir = resolveChild(id) ?: return null
        val file = File(dir, PROJECT_FILE)
        if (!file.isFile) return null
        val project = runCatching {
            json.decodeFromString<EditorProject>(file.readText())
        }.getOrNull() ?: return null
        return if (project.isFutureSchema) project else project.sanitized()
    }

    fun save(project: EditorProject) {
        write(project)
    }

    fun create(name: String): EditorProject = write(EditorProject(name = name))

    fun delete(id: String): Boolean {
        if (id in RESERVED_IDS || id.startsWith(".")) return false
        val dir = resolveChild(id) ?: return false
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    fun sourceFile(projectId: String, hash: String, extension: String): File {
        val dir = resolveChild(projectId) ?: throw IllegalArgumentException("Invalid project id")
        val sources = File(dir, SOURCES_DIR).apply { mkdirs() }
        return File(sources, "$hash.$extension")
    }

    fun peaksFile(projectId: String, hash: String): File {
        val dir = resolveChild(projectId) ?: throw IllegalArgumentException("Invalid project id")
        val peaks = File(dir, PEAKS_DIR).apply { mkdirs() }
        return File(peaks, "$hash.bin")
    }

    fun projectDir(id: String): File = File(root, id)

    fun registerSource(
        projectId: String,
        temp: File,
        hash: String,
        extension: String,
    ): RegisteredSource {
        val destination = sourceFile(projectId, hash, extension)
        if (destination.isFile) {
            temp.delete()
            return RegisteredSource(destination, created = false)
        }
        destination.parentFile?.mkdirs()
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        return RegisteredSource(destination, created = true)
    }

    fun bytesOf(id: String): Long {
        val dir = resolveChild(id) ?: return 0L
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun write(project: EditorProject): EditorProject {
        val dir = resolveChild(project.id)
            ?: throw IllegalArgumentException("Invalid project id")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Could not create project directory")
        }
        val stamped = project.copy(updatedAt = System.currentTimeMillis())
        val file = File(dir, PROJECT_FILE)
        val tmp = File(dir, TMP_FILE)
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(stamped).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return stamped
    }

    /**
     * Resolves a direct child of [root], refusing blank ids and anything that
     * canonicalizes outside the store (including the store root itself).
     */
    private fun resolveChild(id: String): File? {
        if (id.isBlank()) return null
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val child = File(root, id)
            if (child.canonicalFile.parentFile == canonicalRoot) child else null
        }.getOrNull()
    }

    private companion object {
        const val PROJECT_FILE = "project.json"
        const val TMP_FILE = "project.json.tmp"
        const val SOURCES_DIR = "sources"
        const val PEAKS_DIR = "peaks"
        val RESERVED_IDS = setOf(SOURCES_DIR, PEAKS_DIR, PROJECT_FILE)
    }
}
