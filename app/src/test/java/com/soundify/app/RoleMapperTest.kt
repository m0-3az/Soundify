package com.soundify.app

import com.soundify.app.data.RoleMapper
import com.soundify.app.data.StemRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleMapperTest {

    @Test
    fun mapsVocals() {
        assertEquals(StemRole.VOCALS, RoleMapper.infer("Song - Vocals.wav"))
        assertEquals(StemRole.VOCALS, RoleMapper.infer("vox.flac"))
    }

    @Test
    fun mapsDrums() {
        assertEquals(StemRole.DRUMS, RoleMapper.infer("drums.mp3"))
        assertEquals(StemRole.DRUMS, RoleMapper.infer("percussion.wav"))
    }

    @Test
    fun mapsBass() {
        assertEquals(StemRole.BASS, RoleMapper.infer("Bass Guitar.m4a"))
    }

    @Test
    fun mapsGuitarAndKeys() {
        assertEquals(StemRole.GUITAR, RoleMapper.infer("guitar.wav"))
        assertEquals(StemRole.KEYS, RoleMapper.infer("piano.wav"))
        assertEquals(StemRole.KEYS, RoleMapper.infer("keys.wav"))
    }

    @Test
    fun mapsOther() {
        assertEquals(StemRole.OTHER, RoleMapper.infer("instrumental.wav"))
        assertEquals(StemRole.OTHER, RoleMapper.infer("other.flac"))
    }

    @Test
    fun fallsBackToCustom() {
        assertEquals(StemRole.CUSTOM, RoleMapper.infer("track-01.wav"))
    }
}
