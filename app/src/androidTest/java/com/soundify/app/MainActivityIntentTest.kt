package com.soundify.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntentTest {

    @Test
    fun cleanVideoExtraIsConsumedOnlyOnce() {
        val intent = Intent(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_OPEN_CLEAN_VIDEO, true)

        assertTrue(MainActivity.consumeOpenCleanVideoExtra(intent))
        assertFalse(intent.hasExtra(MainActivity.EXTRA_OPEN_CLEAN_VIDEO))
        assertFalse("a recreated activity must not reroute", MainActivity.consumeOpenCleanVideoExtra(intent))
    }

    @Test
    fun unrelatedIntentIsNotRouted() {
        assertFalse(MainActivity.consumeOpenCleanVideoExtra(null))
        assertFalse(MainActivity.consumeOpenCleanVideoExtra(Intent(Intent.ACTION_MAIN)))
        assertFalse(
            MainActivity.consumeOpenCleanVideoExtra(
                Intent(Intent.ACTION_MAIN).putExtra(MainActivity.EXTRA_OPEN_CLEAN_VIDEO, false)
            )
        )
    }
}
