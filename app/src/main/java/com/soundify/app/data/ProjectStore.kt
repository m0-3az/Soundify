package com.soundify.app.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProjectStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val root: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> {
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> load(dir.name) }
            .sortedByDescending { it.updatedAt }
    }

    fun load(id: String): Project? {
        val file = File(projectDir(id), "project.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<Project>(file.readText()) }.getOrNull()
    }

    fun save(project: Project) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val file = File(dir, "project.json")
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(json.encodeToString(project))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        projectDir(id).deleteRecursively()
    }

    fun projectDir(id: String): File = File(root, id)

    fun stemsDir(id: String): File = File(projectDir(id), "stems").apply { mkdirs() }

    fun exportsDir(): File = File(context.filesDir, "exports").apply { mkdirs() }

    fun storageUsedBytes(): Long {
        fun sizeOf(file: File): Long {
            if (!file.exists()) return 0L
            if (file.isFile) return file.length()
            return file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        }
        return sizeOf(root) + sizeOf(exportsDir())
    }
}
