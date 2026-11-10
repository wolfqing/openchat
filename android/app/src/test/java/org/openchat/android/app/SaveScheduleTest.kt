package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveScheduleTest {
    @Test
    fun aSteadyStreamOfChangesCannotPutTheSaveOffForGood() {
        val s = SaveSchedule(quietMs = 500, maxWaitMs = 5_000)
        // A change every 100 ms, as announces arrive in a crowd: each one used to push the save back by half a second.
        var now = 0L
        while (now <= 4_500) { assertEquals(500, s.delayAfterChange(now)); now += 100 }
        assertEquals(400, s.delayAfterChange(4_600))
        assertEquals(0, s.delayAfterChange(5_000))
        assertEquals(0, s.delayAfterChange(9_999)) // overdue stays due
        s.saved()
        assertEquals(500, s.delayAfterChange(10_000))
    }

    @Test
    fun aLoneChangeWaitsOnlyForQuiet() {
        val s = SaveSchedule()
        assertEquals(500, s.delayAfterChange(1_000))
        s.saved()
        assertEquals(500, s.delayAfterChange(60_000))
    }

    @Test
    fun askingTheMeshThreadNeverWaitsForGood() {
        // Answered: the value comes back.
        assertEquals(7, askMesh({ it.run(); true }, 1_000, -1) { 7 })
        // The thread has quit, so the question is refused: the fallback, at once.
        var started = System.nanoTime()
        assertEquals(-1, askMesh({ false }, 10_000, -1) { 7 })
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
        // Accepted but never run (a stuck thread): the fallback after the timeout, not a hang.
        started = System.nanoTime()
        assertEquals(-1, askMesh({ true }, 50, -1) { 7 })
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        // The work blew up over there: the fallback, not an exception on the UI thread.
        assertEquals(null, askMesh<String?>({ it.run(); true }, 1_000, null) { error("boom") })
    }
}
