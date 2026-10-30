package org.openchat.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CashuTest {
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun v3(amounts: List<Long>, mint: String = "https://mint.example.com", unit: String? = "sat", memo: String? = null): String {
        val proofs = JSONArray().apply { amounts.forEach { put(JSONObject().put("amount", it).put("id", "009a1f293253e41e").put("secret", "s").put("C", "02c")) } }
        val o = JSONObject().put("token", JSONArray().put(JSONObject().put("mint", mint).put("proofs", proofs)))
        unit?.let { o.put("unit", it) }; memo?.let { o.put("memo", it) }
        return "cashuA" + b64(o.toString().toByteArray())
    }

    // A tiny definite-length CBOR writer, independent of the reader under test.
    private fun head(major: Int, v: Long): ByteArray = when {
        v < 24 -> byteArrayOf(((major shl 5) or v.toInt()).toByte())
        v < 0x100 -> byteArrayOf(((major shl 5) or 24).toByte(), v.toByte())
        v < 0x10000 -> byteArrayOf(((major shl 5) or 25).toByte(), (v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(((major shl 5) or 26).toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }
    private fun uint(v: Long) = head(0, v)
    private fun bytes(b: ByteArray) = head(2, b.size.toLong()) + b
    private fun text(s: String) = s.toByteArray().let { head(3, it.size.toLong()) + it }
    private fun array(items: List<ByteArray>) = items.fold(head(4, items.size.toLong())) { a, b -> a + b }
    private fun map(pairs: List<Pair<String, ByteArray>>) = pairs.fold(head(5, pairs.size.toLong())) { a, (k, v) -> a + text(k) + v }

    private fun v4(amounts: List<Long>, memo: String? = null): String {
        val pairs = mutableListOf("m" to text("https://mint.example.com"), "u" to text("sat"))
        memo?.let { pairs.add("d" to text(it)) }
        val proofs = amounts.map { map(listOf("a" to uint(it), "s" to text("secret"), "c" to bytes(byteArrayOf(2, 0xAB.toByte())))) }
        pairs.add("t" to array(listOf(map(listOf("i" to bytes(byteArrayOf(0, 0xAD.toByte())), "p" to array(proofs))))))
        return "cashuB" + b64(map(pairs))
    }

    @Test
    fun v3AndV4Decode() {
        val a = Cashu.decode(v3(listOf(2, 8), memo = "thanks!"))!!
        assertEquals(Cashu.Info('A', 10, "sat", "mint.example.com", "thanks!"), a)
        assertEquals("10 sat", a.display)
        assertEquals("5 sat", Cashu.decode(v3(listOf(5), unit = null))!!.display)
        val b = Cashu.decode(v4(listOf(1, 4, 16), memo = "Thank you"))!!
        assertEquals(Cashu.Info('B', 21, "sat", "mint.example.com", "Thank you"), b)
    }

    @Test
    fun strictModeRefusesWhatIsNotMoney() {
        val junk = "cashuB" + b64(byteArrayOf(-1, -1, -1, -1, 0, 1, 2))
        assertEquals('B', Cashu.decode(junk)!!.version)
        assertNull(Cashu.decode(junk, strict = true))
        val good = v4(listOf(1, 4, 16))
        assertNull(Cashu.decode(good.dropLast(12), strict = true))
        assertNull(Cashu.decode(v3(listOf(0)), strict = true))
        assertEquals(21L, Cashu.decode(good, strict = true)!!.amount)
    }

    @Test
    fun linksAndTextDetection() {
        val t = v3(listOf(7))
        listOf("cashu:$t", "cashu://$t", "CASHU:$t").forEach { assertEquals(t, Cashu.bareToken(it)) }
        assertEquals(7L, Cashu.decode("cashu:" + java.net.URLEncoder.encode(t, "UTF-8"))!!.amount)
        assertNull(Cashu.bareToken("hello world"))
        assertNull(Cashu.bareToken("cashuC" + "a".repeat(50)))
        assertNull(Cashu.bareToken("cashuA{not-base64!}"))
        assertNull(Cashu.bareToken("cashuA" + "Q".repeat(500_000)))
        assertEquals(listOf(t), Cashu.tokensIn("here you go: cashu:$t enjoy! $t"))
        assertTrue(Cashu.tokensIn("just talking about cashu here").isEmpty())
    }

    @Test
    fun hostileInputFailsClosed() {
        val v = v3(listOf(1, 2, 4, 8), memo = "memo"); val w = v4(listOf(1, 2, 4, 8), memo = "memo")
        for (t in listOf(v, w)) for (n in 0 until t.length step 3) Cashu.decode(t.take(n))
        assertNull(Cashu.decode(v.dropLast(10)))
        val rnd = java.util.Random(7)
        repeat(200) { val junk = ByteArray(rnd.nextInt(600)).also(rnd::nextBytes); Cashu.decode("cashuA" + b64(junk)); Cashu.decode("cashuB" + b64(junk)) }
        assertNull(Cashu.decode(v3(listOf(Long.MAX_VALUE / 2)))!!.amount)
        assertNull(Cashu.decode(v3(List(3) { 1_500_000_000_000_000L })))
        var nested = uint(1); repeat(64) { nested = array(listOf(nested)) }
        assertNull(Cashu.decode("cashuB" + b64(nested))?.amount)
        val memo = Cashu.decode(v3(listOf(1), memo = "line1\nline2\u0007" + "x".repeat(300)))!!.memo!!
        assertTrue('\n' !in memo && '\u0007' !in memo && memo.length <= 80)
    }
}
