package com.soundify.app.data

import android.content.Context
import com.soundify.app.ui.theme.ThemeAccent
import com.soundify.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("soundify_theme", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _accent = MutableStateFlow(readAccent())
    val accent: StateFlow<ThemeAccent> = _accent.asStateFlow()

    fun setMode(value: ThemeMode) {
        _mode.value = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }

    fun setAccent(value: ThemeAccent) {
        _accent.value = value
        prefs.edit().putString(KEY_ACCENT, value.name).apply()
    }

    private fun readMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(prefs.getString(KEY_MODE, null) ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    private fun readAccent(): ThemeAccent = runCatching {
        ThemeAccent.valueOf(prefs.getString(KEY_ACCENT, null) ?: ThemeAccent.MINT.name)
    }.getOrDefault(ThemeAccent.MINT)

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ACCENT = "accent"
    }
}
