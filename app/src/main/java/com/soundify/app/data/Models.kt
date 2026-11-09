package com.soundify.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class StemRole(val label: String) {
    VOCALS("Vocals"),
    DRUMS("Drums"),
    BASS("Bass"),
    GUITAR("Guitar"),
    KEYS("Keys"),
    OTHER("Other"),
    CUSTOM("Stem");

    companion object {
        fun defaultRoles(): List<StemRole> = listOf(VOCALS, DRUMS, BASS, OTHER)
    }
}

@Serializable
data class Stem(
    val id: String = UUID.randomUUID().toString(),
    val role: StemRole = StemRole.CUSTOM,
    val displayName: String,
    val filePath: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val offsetMs: Long = 0L,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val sortOrder: Int = 0
) {
    val durationSamples: Long
        get() = durationMs * sampleRate / 1000L
}

@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sampleRate: Int,
    val durationMs: Long,
    val loopStartMs: Long = 0L,
    val loopEndMs: Long = durationMs,
    val loopEnabled: Boolean = false,
    val masterGain: Float = 1f,
    val lastPositionMs: Long = 0L,
    val stems: List<Stem> = emptyList()
) {
    val stemCount: Int get() = stems.size

    fun updated(stemId: String, transform: (Stem) -> Stem): Project =
        copy(
            updatedAt = System.currentTimeMillis(),
            stems = stems.map { if (it.id == stemId) transform(it) else it }
        )
}
