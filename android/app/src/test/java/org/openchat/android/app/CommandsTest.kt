package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.app.Commands.Note
import org.openchat.android.app.Commands.Outcome

class CommandsTest {
    private val libai = Commands.Person(PeerLabel.mesh("李白", "aaaa111122223333"), "aaaa111122223333")
    private val darwin1 = Commands.Person(PeerLabel.mesh("Darwin", "bbbb111122223333"), "bbbb111122223333")
    private val darwin2 = Commands.Person(PeerLabel.mesh("Darwin", "cccc111122223333"), "cccc111122223333")
    private val people = listOf(libai, darwin1, darwin2)

    private fun run(t: String, conv: String = "mesh", dm: Commands.Person? = null) = Commands.run(t, conv, "苏轼", people, dm)

    @Test
    fun msgOpensAPrivateChatAndResolvesSuffixes() {
        assertEquals(Outcome.OpenPrivate(libai.key, "明月几时有"), run("/msg @李白 明月几时有"))
        assertEquals(Outcome.OpenPrivate(libai.key, null), run("/m 李白"))
        assertEquals(Outcome.OpenPrivate(darwin2.key, null), run("/msg @darwin#cccc"))
        assertEquals(Outcome.Local(Note.NOT_FOUND, "@Darwin"), run("/msg @Darwin")) // two of them: ambiguous
        assertEquals(Outcome.Local(Note.MESH_ONLY), run("/msg @李白", conv = "geo:wtw3s"))
    }

    @Test
    fun emotesUseBitchatWording() {
        assertEquals(Outcome.Send("* 🫂 苏轼 hugs 李白 *"), run("/hug @李白"))
        assertEquals(Outcome.Send("* 🐟 苏轼 slaps 李白 around a bit with a large trout *"), run("/slap 李白"))
        assertEquals(Outcome.Send("* 🫂 苏轼 hugs you *"), run("/hug", conv = "dm:${libai.key}", dm = libai))
    }

    @Test
    fun whoBlockHelpAndUnknown() {
        assertEquals(Outcome.Local(Note.WHO, "李白#aaaa, Darwin#bbbb, Darwin#cccc"), run("/who"))
        assertEquals(Outcome.Local(Note.WHO_NONE), Commands.run("/who", "mesh", "苏轼", emptyList(), null))
        assertEquals(Outcome.Block(libai), run("/block @李白"))
        assertEquals(Outcome.Unblock("李白"), run("/unblock @李白"))
        assertEquals(Outcome.Local(Note.HELP), run("/help"))
        assertEquals(Outcome.Clear, run("/clear"))
        assertEquals(Outcome.Local(Note.UNKNOWN, "/dance"), run("/dance now"))
        assertEquals(Outcome.Favorite(libai, true), run("/fav @李白"))
        assertTrue(Commands.isCommand("/who"))
        assertFalse(Commands.isCommand("/"))
        assertFalse(Commands.isCommand("// not a command"))
    }

    @Test
    fun dropLeavesANoteFromLocationChannels() {
        assertEquals(Outcome.Drop("water at the north gate"), run("/drop water at the north gate", conv = "geo:wtw3sjq"))
        assertEquals(Outcome.Local(Note.USAGE, "/drop message"), run("/drop", conv = "geo:wtw3sjq"))
        assertTrue("/drop" in Commands.available("geo:wtw3s"))
        assertFalse("/drop" in Commands.available("mesh"))
    }
}
