package org.openchat.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private var now = 1_000_000L
    private val limiter = RateLimiter(clock = { now })

    @Test
    fun aSenderGetsABurstOfFiveThenOnePerSecond() {
        repeat(5) { assertTrue(limiter.allow("darwin", "note $it")) }
        assertFalse(limiter.allow("darwin", "note 5"))
        assertTrue(limiter.allow("curie", "unrelated")) // others are unaffected
        now += 1_000
        assertTrue(limiter.allow("darwin", "note 6"))
        assertFalse(limiter.allow("darwin", "note 7"))
    }

    @Test
    fun theSameTextFromManySendersIsCappedToo() {
        assertTrue(limiter.allow("a", "BUY NOW https://x.example/p?ref=1"))
        assertTrue(limiter.allow("b", "buy   now https://x.example/p?ref=2"))
        assertTrue(limiter.allow("c", "Buy now https://x.example/p#top"))
        assertFalse(limiter.allow("d", "buy now https://x.example/p"))
        now += 2_000
        assertTrue(limiter.allow("e", "buy now https://x.example/p"))
    }

    @Test
    fun proofOfWorkPaysForTheSender() {
        repeat(5) { limiter.allow("lovelace", "m$it") }
        assertFalse(limiter.allow("lovelace", "m5"))
        assertTrue(limiter.allow("lovelace", "m6", powBits = 8))
        assertEquals("buy now https://x.example/p", RateLimiter.normalize("  BUY\tnow  https://x.example/p?utm=1 "))
    }
}
