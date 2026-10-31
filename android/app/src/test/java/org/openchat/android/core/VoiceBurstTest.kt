package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBurstTest {
    private val id = ByteArray(8) { (it + 1).toByte() }
    private var now = 1_000_000L
    private fun frames(seq: Int, vararg sizes: Int) = VoiceBurst(id, seq, VoiceBurst.Kind.Frames(sizes.map { ByteArray(it) { s -> s.toByte() } }))

    @Test
    fun packetsMatchBitchatsLayout() {
        assertEquals("0102030405060708" + "0000" + "01" + "01", VoiceBurst(id, 0, VoiceBurst.Kind.Start(1)).encode().toHex())
        assertEquals("0102030405060708" + "0003" + "02" + "0002" + "00000a00", VoiceBurst(id, 3, VoiceBurst.Kind.End(2, 2560)).encode().toHex())
        assertEquals("0102030405060708" + "0001" + "04", VoiceBurst(id, 1, VoiceBurst.Kind.Canceled).encode().toHex())
        val f = frames(1, 3, 2).encode()
        assertEquals("0102030405060708" + "0001" + "00" + "0003000102" + "00020001", f.toHex())
        val back = VoiceBurst.decode(f)!!
        assertEquals(listOf(3, 2), (back.kind as VoiceBurst.Kind.Frames).frames.map { it.size })
        assertNull("unknown codec", VoiceBurst.decode(VoiceBurst(id, 0, VoiceBurst.Kind.Start(9)).encode()))
        assertNull("zero-length frame", VoiceBurst.decode(id + byteArrayOf(0, 1, 0, 0, 0)))
        assertNull("unknown flags", VoiceBurst.decode(id + byteArrayOf(0, 1, 0x10)))
    }

    @Test
    fun packetizerKeepsEachPacketWithinTheBudget() {
        val p = VoicePacketizer(id)
        val out = ArrayList<ByteArray>()
        repeat(10) { out += p.add(ByteArray(60)) }
        out += p.flush()
        assertTrue(out.all { it.size <= 210 })
        assertEquals(10, out.sumOf { (VoiceBurst.decode(it)!!.kind as VoiceBurst.Kind.Frames).frames.size })
        assertEquals((1..out.size).toList(), out.map { VoiceBurst.decode(it)!!.seq })
        assertEquals(out.size, p.dataPackets)
        assertEquals(out.size + 1, VoiceBurst.decode(p.control(VoiceBurst.Kind.Canceled))!!.seq)
    }

    @Test
    fun adtsHeaderDescribesAacLc16kMono() {
        val f = Adts.frame(ByteArray(100))
        assertEquals("fff1" + "60" + "40", f.copyOf(4).toHex())
        assertEquals(107, ((f[3].toInt() and 3) shl 11) or ((f[4].toInt() and 0xFF) shl 3) or ((f[5].toInt() and 0xFF) ushr 5))
    }

    @Test
    fun assemblyReordersSkipsHolesAndEnds() {
        val a = BurstAssembly { now }
        assertEquals(0, a.accept(frames(2, 1), 20).size) // waits for 1
        assertEquals(2, a.accept(frames(1, 1), 20).size) // 1 then 2
        assertEquals(0, a.accept(frames(4, 1), 20).size) // 3 is missing
        now += 600
        assertEquals(1, a.tick().size) // gave up on 3
        val out = a.accept(VoiceBurst(id, 5, VoiceBurst.Kind.End(4, 300)), 17)
        assertEquals(0, out.size)
        assertEquals(BurstAssembly.State.FINISHED, a.state)
        assertEquals(3, a.delivered)
    }

    @Test
    fun assemblyCancelsGoesQuietAndCapsFloods() {
        val c = BurstAssembly { now }
        c.accept(frames(1, 1), 20); c.accept(VoiceBurst(id, 2, VoiceBurst.Kind.Canceled), 11)
        assertEquals(BurstAssembly.State.CANCELED, c.state)
        val q = BurstAssembly { now }
        q.accept(frames(1, 1), 20)
        now += BurstAssembly.IDLE_MS
        q.tick()
        assertEquals(BurstAssembly.State.FINISHED, q.state)
        val f = BurstAssembly { now }
        f.accept(frames(1, 1), 13_000)
        assertEquals(BurstAssembly.State.FINISHED, f.state)
        assertArrayEquals(byteArrayOf(0), (frames(1, 1).kind as VoiceBurst.Kind.Frames).frames[0])
    }
}
