package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryTest {

    @Test
    fun undoReturnsThePreviousSnapshotAndRedoRestoresIt() {
        val history = EditorHistory<Int>()
        history.record(1)
        history.record(2)

        assertEquals(2, history.undo(3))
        assertEquals(1, history.undo(2))
        assertFalse(history.canUndo)

        assertEquals(2, history.redo(1))
        assertEquals(3, history.redo(2))
        assertFalse(history.canRedo)
    }

    @Test
    fun undoAndRedoAreNoOpsAtTheBounds() {
        val history = EditorHistory<Int>()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo(7))
        assertNull(history.redo(7))

        history.record(1)
        assertEquals(1, history.undo(2))
        assertNull("a second undo must not remove anything", history.undo(1))
        assertEquals(2, history.redo(1))
        assertNull("a second redo must not invent a snapshot", history.redo(2))
    }

    @Test
    fun theUndoStackKeepsTheMostRecentFiftySnapshots() {
        val history = EditorHistory<Int>()
        repeat(60) { value -> history.record(value) }

        assertEquals(50, history.undoSize)

        var current = 60
        repeat(50) { step ->
            current = history.undo(current) ?: error("undo $step returned null")
        }
        assertNull("the oldest ten snapshots must be dropped", history.undo(current))
        assertEquals("the oldest kept snapshot is value 10", 10, current)
    }

    @Test
    fun redoStackIsAlsoCappedAtFifty() {
        val history = EditorHistory<Int>()
        repeat(60) { value -> history.record(value) }
        var current = 60
        repeat(50) { current = history.undo(current) ?: error("undo failed") }

        assertEquals(50, history.redoSize)
    }

    @Test
    fun sameKeyWithinTheWindowCoalescesIntoOneStep() {
        var now = 1_000L
        val history = EditorHistory<Int>(clock = { now })
        history.record(1, key = "volume:clip-a")
        now += 100
        history.record(2, key = "volume:clip-a")
        now += 100
        history.record(3, key = "volume:clip-a")

        assertEquals("three drag updates must undo as one step", 1, history.undoSize)
        assertEquals(1, history.undo(4))
        assertFalse(history.canUndo)
    }

    @Test
    fun aDifferentKeyStartsANewStep() {
        var now = 1_000L
        val history = EditorHistory<Int>(clock = { now })
        history.record(1, key = "volume:clip-a")
        now += 100
        history.record(2, key = "fade:clip-a")

        assertEquals(2, history.undoSize)
        assertEquals(2, history.undo(3))
        assertEquals(1, history.undo(2))
    }

    @Test
    fun anExpiredWindowStartsANewStep() {
        var now = 1_000L
        val history = EditorHistory<Int>(clock = { now })
        history.record(1, key = "volume:clip-a")
        now += 501
        history.record(2, key = "volume:clip-a")

        assertEquals(2, history.undoSize)
        assertEquals(2, history.undo(3))
        assertEquals(1, history.undo(2))
    }

    @Test
    fun editsWithoutAKeyNeverCoalesce() {
        var now = 1_000L
        val history = EditorHistory<Int>(clock = { now })
        history.record(1)
        now += 10
        history.record(2)
        now += 10
        history.record(3)

        assertEquals(3, history.undoSize)
    }

    @Test
    fun aNewRecordClearsTheRedoStack() {
        val history = EditorHistory<Int>()
        history.record(1)
        assertEquals(1, history.undo(2))
        assertTrue(history.canRedo)

        history.record(9)
        assertFalse("a divergent edit must not keep a stale redo", history.canRedo)
        assertNull(history.redo(10))
    }

    @Test
    fun resetClearsBothStacksAndCoalescing() {
        var now = 1_000L
        val history = EditorHistory<Int>(clock = { now })
        history.record(1, key = "trim:clip-a")
        assertEquals(1, history.undo(2))
        history.reset()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(0, history.undoSize)

        history.record(5, key = "trim:clip-a")
        assertEquals("after reset the first record must always push", 1, history.undoSize)
        now += 10
        history.record(6, key = "trim:clip-a")
        assertEquals("coalescing resumes after reset", 1, history.undoSize)
        assertEquals(5, history.undo(7))
    }
}
