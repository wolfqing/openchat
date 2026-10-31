package org.openchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDutyCycleTest {
    @Test
    fun sparseAndDenseWindowsFollowTheSpec() {
        assertEquals(5_000L, ScanDutyCycle.onMs(0))
        assertEquals(10_000L, ScanDutyCycle.offMs(5))
        assertEquals(3_000L, ScanDutyCycle.onMs(6))
        assertEquals(15_000L, ScanDutyCycle.offMs(9))
    }

    @Test
    fun noScanWhileTheCentralTableIsFull() {
        assertTrue(ScanDutyCycle.wanted(centralLinks = 3, connecting = 2, cap = 6))
        assertFalse(ScanDutyCycle.wanted(centralLinks = 4, connecting = 2, cap = 6))
        assertFalse(ScanDutyCycle.wanted(centralLinks = 6, connecting = 0, cap = 6))
    }

    @Test
    fun failedStartsBackOffToAMinute() {
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L), (1..5).map { ScanDutyCycle.retryMs(it) })
        assertEquals(10_000L, ScanDutyCycle.retryMs(0))
    }
}
