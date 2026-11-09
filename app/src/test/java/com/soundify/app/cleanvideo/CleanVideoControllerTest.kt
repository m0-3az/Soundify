package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanVideoControllerTest {

    @Test
    fun workerProgressCannotOverwriteCancellingState() {
        val processing = CleanVideoController.State(
            isProcessing = true,
            stage = CleanStage.SEPARATING,
            detail = "Chunk 2 of 10",
            chunkIndex = 2,
            chunkCount = 10,
            etaSeconds = 30,
        )
        val cancelling = processing.copy(
            cancelling = true,
            stage = CleanStage.FINALIZING,
            detail = "Cancelling…",
        )

        val after = CleanVideoController.reduceProgress(
            cancelling,
            CleanProgress(0.5f, CleanStage.SEPARATING, "Chunk 3 of 10", 3, 10, 20),
        )

        assertTrue(after.isProcessing)
        assertTrue(after.cancelling)
        assertEquals("Cancelling…", after.detail)
        assertEquals(2, after.chunkIndex)
    }

    @Test
    fun normalProgressIsAppliedWhileProcessing() {
        val processing = CleanVideoController.State(isProcessing = true)

        val after = CleanVideoController.reduceProgress(
            processing,
            CleanProgress(0.42f, CleanStage.SEPARATING, "Chunk 4 of 9", 4, 9, 12),
        )

        assertEquals(0.42f, after.progress, 0.0001f)
        assertEquals(CleanStage.SEPARATING, after.stage)
        assertEquals("Chunk 4 of 9", after.detail)
        assertEquals(4, after.chunkIndex)
        assertEquals(9, after.chunkCount)
        assertEquals(12L, after.etaSeconds)
        assertFalse(after.cancelling)
    }

    @Test
    fun progressIsIgnoredAfterProcessingStops() {
        val idle = CleanVideoController.State(isProcessing = false, detail = "Cancelled")

        val after = CleanVideoController.reduceProgress(
            idle,
            CleanProgress(0.8f, CleanStage.SEPARATING, "Chunk 8 of 10"),
        )

        assertEquals(idle, after)
    }
}
