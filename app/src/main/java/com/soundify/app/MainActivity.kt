package com.soundify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soundify.app.data.ThemePreferences
import com.soundify.app.ui.SoundifyApp
import com.soundify.app.ui.theme.SoundifyTheme
import com.soundify.app.ui.theme.isDarkMode
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val themePreferences by lazy { ThemePreferences(applicationContext) }
    private val openCleanVideo = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLaunchTheme()
        super.onCreate(savedInstanceState)
        // Only route on a fresh launch; a recreation must not reopen Clean
        // Video because the original launch intent still carries the extra.
        if (savedInstanceState == null) handleIntent(intent)
        requestNotificationPermissionIfNeeded()
        setContent {
            val mode by themePreferences.mode.collectAsStateWithLifecycle()
            val accent by themePreferences.accent.collectAsStateWithLifecycle()
            val viewModel: SoundifyViewModel = viewModel()
            val routeToCleanVideo by openCleanVideo.collectAsStateWithLifecycle()
            LaunchedEffect(routeToCleanVideo) {
                if (routeToCleanVideo) {
                    viewModel.openCleanVideo()
                    openCleanVideo.value = false
                }
            }
            SoundifyTheme(mode = mode, accent = accent) {
                SoundifyApp(viewModel, themePreferences)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Pick the fixed light/dark launch style before the window is created, so
     * a stored Dark/AMOLED mode does not flash a light splash on a light system.
     */
    private fun applyLaunchTheme() {
        val systemDark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = isDarkMode(themePreferences.mode.value, systemDark)
        setTheme(if (dark) R.style.Theme_Soundify_Dark else R.style.Theme_Soundify_Light)
    }

    private fun handleIntent(intent: Intent?) {
        if (consumeOpenCleanVideoExtra(intent)) {
            openCleanVideo.value = true
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_CLEAN_VIDEO = "com.soundify.app.OPEN_CLEAN_VIDEO"

        /**
         * Consumes the notification extra so a later recreation of the same
         * launch intent cannot route to Clean Video again.
         */
        internal fun consumeOpenCleanVideoExtra(intent: Intent?): Boolean {
            if (intent?.getBooleanExtra(EXTRA_OPEN_CLEAN_VIDEO, false) != true) return false
            intent.removeExtra(EXTRA_OPEN_CLEAN_VIDEO)
            return true
        }
    }
}
