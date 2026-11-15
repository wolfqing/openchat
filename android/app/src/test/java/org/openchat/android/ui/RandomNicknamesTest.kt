package org.openchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.app.PeerLabel
import java.util.Locale
import kotlin.random.Random

class RandomNicknamesTest {
    @Test
    fun allPoolsPreserveFullNameInPeerLabel() {
        for (pool in listOf(RandomNicknames.ZH_HANS, RandomNicknames.ZH_HANT, RandomNicknames.LATIN)) {
            assertTrue(pool.size >= 40)
            for (name in pool) {
                assertTrue(name.isNotBlank() && name.length <= 32)
                assertEquals(name, PeerLabel.mesh(name, "7c21deadbeef0000").name)
            }
        }
    }

    @Test
    fun nextNeverRepeatsCurrentAndRespectsLocale() {
        val rng = Random(42)
        repeat(30) {
            val current = RandomNicknames.ZH_HANS.first()
            val picked = RandomNicknames.next(current, Locale.SIMPLIFIED_CHINESE, rng)
            assertNotEquals(current, picked)
            assertTrue(picked in RandomNicknames.ZH_HANS)
        }
        repeat(30) {
            val current = RandomNicknames.ZH_HANT.first()
            val picked = RandomNicknames.next(current, Locale.TRADITIONAL_CHINESE, rng)
            assertNotEquals(current, picked)
            assertTrue(picked in RandomNicknames.ZH_HANT)
        }
        repeat(30) {
            val current = RandomNicknames.LATIN.first()
            val picked = RandomNicknames.next(current, Locale.US, rng)
            assertNotEquals(current, picked)
            assertTrue(picked in RandomNicknames.LATIN)
        }
    }
}
