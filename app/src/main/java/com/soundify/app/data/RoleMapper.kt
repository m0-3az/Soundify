package com.soundify.app.data

object RoleMapper {

    fun infer(fileName: String): StemRole {
        val name = fileName.lowercase()
        return when {
            name.contains("vocal") || name.contains("vox") || name.contains("voice") -> StemRole.VOCALS
            name.contains("drum") || name.contains("perc") -> StemRole.DRUMS
            name.contains("bass") -> StemRole.BASS
            name.contains("guitar") -> StemRole.GUITAR
            name.contains("piano") || name.contains("keys") || name.contains("keyboard") -> StemRole.KEYS
            name.contains("other") || name.contains("instrumental") || name.contains("music") -> StemRole.OTHER
            else -> StemRole.CUSTOM
        }
    }

    fun defaultDisplayName(role: StemRole, index: Int): String =
        if (role == StemRole.CUSTOM) "Stem ${index + 1}" else role.label
}
