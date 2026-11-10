package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PanicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun everythingInTheDirectoryGoesNamedOrNot() {
        val dir = tmp.newFolder("no_backup")
        // The sealed files the service knows by name, a write caught half way, and one nobody listed.
        listOf("identity.bin", "mesh-state.bin", "social.bin", "groups.bin", "nostr-seen.bin", "groups.bin.tmp", "added-later.bin")
            .forEach { File(dir, it).writeBytes(ByteArray(300) { 7 }) }
        File(dir, "nested/deeper").mkdirs()
        File(dir, "nested/deeper/x").writeText("secret")

        assertTrue(Panic.eraseDirectory(dir, overwrite = true))

        assertTrue("the directory itself stays", dir.isDirectory)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun anEmptyOrMissingDirectoryIsAlreadyClean() {
        assertTrue(Panic.eraseDirectory(tmp.newFolder("empty"), overwrite = true))
        assertTrue(Panic.eraseDirectory(File(tmp.root, "never-made"), overwrite = false))
    }
}
