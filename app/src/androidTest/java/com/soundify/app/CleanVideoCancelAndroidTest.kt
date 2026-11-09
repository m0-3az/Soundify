package com.soundify.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.cleanvideo.CleanVideoController
import com.soundify.app.cleanvideo.CleanVideoService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanVideoCancelAndroidTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        CleanVideoController.fail("test reset")
        CleanVideoController.reset(context)
    }

    @After
    fun tearDown() {
        CleanVideoController.fail("test cleanup")
        CleanVideoController.reset(context)
    }

    @Test
    fun failedCancelDeliveryLeavesTheUiUsable() {
        startProcessing()
        val failing = object : ContextWrapper(context) {
            override fun startService(service: Intent): ComponentName? {
                throw IllegalStateException("cancel blocked")
            }
        }

        CleanVideoController.cancel(failing)

        val state = CleanVideoController.state.value
        assertTrue("processing should continue after a failed cancel", state.isProcessing)
        assertFalse("cancelling flag must be cleared", state.cancelling)
        assertTrue(
            "the cancel failure should be surfaced: ${state.error}",
            state.error?.contains("Could not stop") == true,
        )
    }

    @Test
    fun deliveredCancelSetsTheCancellingFlag() {
        startProcessing()
        var sent: Intent? = null
        val recording = object : ContextWrapper(context) {
            override fun startService(service: Intent): ComponentName? {
                sent = service
                return ComponentName(context, CleanVideoService::class.java)
            }
        }

        CleanVideoController.cancel(recording)

        val state = CleanVideoController.state.value
        assertTrue(state.isProcessing)
        assertTrue(state.cancelling)
        assertEquals(CleanVideoService.ACTION_CANCEL, sent?.action)
    }

    @Test
    fun dataSyncTimeoutOverloadIsOverridden() {
        val method = CleanVideoService::class.java.getDeclaredMethod(
            "onTimeout",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        assertEquals(CleanVideoService::class.java, method.declaringClass)
    }

    private fun startProcessing() {
        val launcher = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = null
        }
        val source = File(context.cacheDir, "cancel-test.mp4")
        CleanVideoController.start(launcher, Uri.fromFile(source), source.name)
        assertTrue("processing state did not start", CleanVideoController.state.value.isProcessing)
    }
}
