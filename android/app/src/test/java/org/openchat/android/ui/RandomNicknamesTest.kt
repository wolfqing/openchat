package org.openchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.app.PeerLabel
import org.openchat.android.app.RandomNicknames
import org.openchat.android.mesh.Identity
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

    @Test
    fun generatedIdentityUsesRandomCallsignInsteadOfAnon() {
        repeat(15) {
            val id = Identity.generate()
            assertTrue(id.nickname.isNotBlank())
            assertNotEquals("anon", id.nickname.lowercase(Locale.ROOT))
        }
        val zhName = Identity.defaultNickname(Locale.SIMPLIFIED_CHINESE)
        assertTrue(zhName in RandomNicknames.ZH_HANS)
        val enName = Identity.defaultNickname(Locale.US)
        assertTrue(enName in RandomNicknames.LATIN)
    }

    @Test
    fun legacyAnonMigratesOncePreservingKeysAndLeavesCustomNamesAlone() {
        val rng = Random(99)
        val legacy = Identity.generate("anon")
        val migrated = RandomNicknames.migrateIfDefaultAnon(legacy, alreadyMigrated = false, locale = Locale.SIMPLIFIED_CHINESE, random = rng)
        assertNotNull(migrated)
        assertTrue(migrated!!.nickname in RandomNicknames.ZH_HANS)
        assertEquals("peerId must stay identical", legacy.peerIdHex, migrated.peerIdHex)
        assertEquals("fingerprint must stay identical", legacy.fingerprint, migrated.fingerprint)

        // Once migrated, an intentional manual "anon" is respected; blank still heals.
        assertNull(RandomNicknames.migrateIfDefaultAnon(legacy, alreadyMigrated = true, locale = Locale.US, random = rng))
        assertNotNull(RandomNicknames.migrateIfDefaultAnon(Identity.generate("   "), alreadyMigrated = true, locale = Locale.US, random = rng))

        // Custom nicknames are never overwritten.
        val custom = Identity.generate("alice")
        assertNull(RandomNicknames.migrateIfDefaultAnon(custom, alreadyMigrated = false, locale = Locale.US, random = rng))
    }
}
