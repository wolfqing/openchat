package org.openchat.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {
    @Test
    fun findsTokensWithAndWithoutSuffix() {
        assertEquals(listOf("李白", "darwin#a1b2"), Mentions.names("hi @李白 and @darwin#a1b2, see you"))
        assertEquals(emptyList<String>(), Mentions.names("mail me at curie@example.org"))
        assertEquals(listOf(0..7), Mentions.ranges("@苏轼#0f0f!"))
    }

    @Test
    fun mentionOfMeRespectsAmbiguity() {
        assertTrue(Mentions.mentionsMe("thanks @Lovelace", "lovelace", "e887", ambiguous = false))
        assertFalse(Mentions.mentionsMe("thanks @Lovelace", "lovelace", "e887", ambiguous = true))
        assertTrue(Mentions.mentionsMe("thanks @lovelace#E887", "Lovelace", "e887", ambiguous = true))
        assertFalse(Mentions.mentionsMe("thanks @lovelace#1234", "Lovelace", "e887", ambiguous = false))
        assertFalse(Mentions.mentionsMe("thanks @lovelacex", "Lovelace", "e887", ambiguous = false))
    }

    @Test
    fun typingFindsTheWordAfterTheLastAt() {
        assertEquals("da", Mentions.typing("hello @da"))
        assertEquals("", Mentions.typing("@"))
        assertNull(Mentions.typing("curie@ex"))
        assertNull(Mentions.typing("@darwin said hi"))
    }
}
