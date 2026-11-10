package com.soundify.app.editor

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToLong

data class ImportedSource(
    val hash: String,
    val file: File,
    val displayName: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    /** False when the same hash was already registered for this project. */
    val created: Boolean,
)

class EditorImporter(
    private val context: Context,
    private val store: EditorStore,
) {

    suspend fun import(projectId: String, uri: Uri, displayName: String?): ImportedSource =
        withContext(Dispatchers.IO) {
            val cacheRoot = File(context.cacheDir, "editor").apply { mkdirs() }
            val temp = File(cacheRoot, "import.tmp")
            var registered: RegisteredSource? = null
            try {
                val hash = copyAndHash(uri, temp)
                registered = store.registerSource(projectId, temp, hash, extensionOf(displayName))
                val audio = try {
                    probe(registered.file)
                } catch (error: Exception) {
                    null
                } ?: throw IllegalStateException(NO_AUDIO)
                ImportedSource(
                    hash = hash,
                    file = registered.file,
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: registered.file.name,
                    durationMs = audio.durationMs,
                    sampleRate = audio.sampleRate,
                    channels = audio.channels,
                    created = registered.created,
                )
            } catch (error: Exception) {
                // A failed import must not leave a brand-new app copy behind.
                if (registered?.created == true) registered.file.delete()
                throw error
            } finally {
                temp.delete()
            }
        }

    private fun copyAndHash(uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(NO_AUDIO)
        input.use { stream ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extensionOf(displayName: String?): String {
        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.filter { it in 'a'..'z' || it in '0'..'9' }
            ?.take(5)
            .orEmpty()
        return extension.ifEmpty { DEFAULT_EXTENSION }
    }

    private fun probe(file: File): ProbedAudio? {
        val information = runCatching {
            FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation
        }.getOrNull() ?: return null
        val stream = information.streams.orEmpty().firstOrNull { it.type == "audio" }
            ?: return null
        val sampleRate = stream.sampleRate?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val channels = runCatching {
            stream.getNumberProperty("channels")?.toInt()
        }.getOrNull()?.takeIf { it > 0 } ?: return null
        val durationMs = information.duration
            ?.toDoubleOrNull()
            ?.let { (it * 1000.0).roundToLong() }
            ?.takeIf { it > 0 } ?: return null
        return ProbedAudio(durationMs, sampleRate, channels)
    }

    private data class ProbedAudio(
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
    )

    private companion object {
        const val DEFAULT_EXTENSION = "wav"
        const val NO_AUDIO = "This file has no readable audio track."
    }
}
