package org.openchat.android.core

import org.json.JSONObject

/**
 * Cashu ecash tokens (docs/protocol-spec.md §28), read just far enough to show what a
 * message carries: amount, unit, mint and memo. `cashuA` is base64url JSON (V3),
 * `cashuB` base64url CBOR (V4). Tokens are bearer strings sent as ordinary text; the
 * app never talks to a mint. Everything here parses strangers' text, so every path is
 * bounded and fails closed.
 */
object Cashu {
    data class Info(val version: Char, val amount: Long?, val unit: String?, val mintHost: String?, val memo: String?) {
        val display get() = amount?.let { "$it ${unit ?: "sat"}" }
    }

    const val MAX_TOKEN = 60_000
    private const val MAX_AMOUNT = 2_100_000_000_000_000L
    private val TOKEN_CHARS = Regex("[A-Za-z0-9_+/=.\\-]+")
    private val IN_TEXT = Regex("cashu[AB][A-Za-z0-9_+/=.\\-]{6,}")

    /** The bare token in [raw], which may be a `cashu:` or `cashu://` link, percent-encoded or not. */
    fun bareToken(raw: String): String? {
        var t = raw.trim()
        val lower = t.lowercase()
        t = when {
            lower.startsWith("cashu://") -> t.substring(8)
            lower.startsWith("cashu:") -> t.substring(6)
            else -> t
        }
        if ('%' in t) t = try { java.net.URLDecoder.decode(t.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { return null }
        if (t.length < 12 || t.length > MAX_TOKEN) return null
        if (!t.startsWith("cashuA") && !t.startsWith("cashuB")) return null
        return t.takeIf { TOKEN_CHARS.matches(it) }
    }

    /**
     * Summary of a token. Strict mode, for sending, wants a clean decode and a positive
     * amount; otherwise a V4 token we cannot walk still shows as a token without an amount.
     */
    fun decode(raw: String, strict: Boolean = false): Info? {
        val token = bareToken(raw) ?: return null
        val payload = base64Url(token.substring(6))?.takeIf { it.isNotEmpty() } ?: return null
        val info = when (token[5]) {
            'A' -> v3(payload)
            else -> v4(payload) ?: if (strict) return null else Info('B', null, null, null, null)
        } ?: return null
        if (strict && (info.amount ?: 0) <= 0) return null
        return info
    }

    /** Distinct tokens mentioned anywhere in [text], in order. */
    fun tokensIn(text: String): List<String> =
        if ("cashu" !in text) emptyList() else IN_TEXT.findAll(text).mapNotNull { bareToken(it.value) }.distinct().toList()

    private fun base64Url(s: String): ByteArray? {
        val clean = s.replace('-', '+').replace('_', '/').replace("=", "")
        if (clean.length % 4 == 1) return null
        return try { java.util.Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)) } catch (e: Exception) { null }
    }

    private fun v3(payload: ByteArray): Info? = try {
        val o = JSONObject(String(payload, Charsets.UTF_8))
        val entries = o.getJSONArray("token")
        if (entries.length() == 0) null else {
            var total = 0L; var saw = false; var host: String? = null
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                if (host == null) host = mintHost(e.optString("mint"))
                val proofs = e.optJSONArray("proofs") ?: continue
                for (j in 0 until proofs.length()) {
                    val a = proofs.optJSONObject(j)?.opt("amount") as? Number ?: continue
                    val v = a.toLong()
                    if (v <= 0 || v > MAX_AMOUNT || a.toDouble() > MAX_AMOUNT) continue
                    total += v
                    if (total > MAX_AMOUNT) throw IllegalStateException()
                    saw = true
                }
            }
            Info('A', if (saw) total else null, unit(o.optString("unit")), host, memo(o.optString("memo")))
        }
    } catch (e: Exception) { null }

    /** TokenV4: { "m": mint, "u": unit, "d": memo, "t": [ { "i": id, "p": [ { "a": amount, … } ] } ] } */
    private fun v4(payload: ByteArray): Info? {
        val top = Cbor(payload).value(0) as? List<*> ?: return null // maps come back as key/value pair lists
        var host: String? = null; var unit: String? = null; var memo: String? = null
        var total = 0L; var saw = false
        for ((k, v) in top.filterIsInstance<Pair<*, *>>()) {
            when {
                k == "m" && v is String -> host = mintHost(v)
                k == "u" && v is String -> unit = unit(v)
                k == "d" && v is String -> memo = memo(v)
                k == "t" && v is Array<*> -> for (group in v) {
                    val pairs = (group as? List<*>)?.filterIsInstance<Pair<*, *>>() ?: continue
                    for ((gk, gv) in pairs) {
                        if (gk != "p" || gv !is Array<*>) continue
                        for (proof in gv) {
                            for ((pk, pv) in (proof as? List<*>)?.filterIsInstance<Pair<*, *>>().orEmpty()) {
                                if (pk != "a" || pv !is ULong) continue
                                if (pv == 0uL || pv > MAX_AMOUNT.toULong()) continue
                                total += pv.toLong()
                                if (total > MAX_AMOUNT) return null
                                saw = true
                            }
                        }
                    }
                }
            }
        }
        return Info('B', if (saw) total else null, unit, host, memo)
    }

    private fun mintHost(mint: String): String? {
        if (mint.isEmpty() || mint.length > 512) return null
        val host = try { java.net.URI(mint).host } catch (e: Exception) { null } ?: return null
        return host.lowercase().take(48).ifEmpty { null }
    }

    private fun unit(u: String): String? = u.takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit) }

    private fun memo(m: String): String? {
        if (m.isEmpty() || m.length > 512) return null
        return m.filter { !it.isISOControl() }.trim().take(80).ifEmpty { null }
    }

    /**
     * Definite-length CBOR, just enough for TokenV4, bounded in depth and size. Maps become
     * lists of pairs, arrays become Array, unsigned ints ULong, text String; byte strings,
     * tags' contents and simple values are kept only as placeholders.
     */
    private class Cbor(private val b: ByteArray) {
        private var at = 0
        private object Opaque

        fun value(depth: Int): Any? {
            if (depth > 16) return null
            val (major, arg) = head() ?: return null
            return when (major) {
                0 -> arg.toULong()
                1 -> Opaque
                2 -> { bytes(arg) ?: return null; Opaque }
                3 -> String(bytes(arg) ?: return null, Charsets.UTF_8)
                4 -> { if (arg > 10_000) return null; Array(arg.toInt()) { value(depth + 1) ?: return null } }
                5 -> {
                    if (arg > 10_000) return null
                    List(arg.toInt()) { Pair(value(depth + 1) ?: return null, value(depth + 1) ?: return null) }
                }
                6 -> value(depth + 1)
                else -> Opaque
            }
        }

        private fun head(): Pair<Int, Long>? {
            if (at >= b.size) return null
            val first = b[at++].toInt() and 0xFF
            val major = first ushr 5
            val info = first and 0x1F
            val arg = when (info) {
                in 0..23 -> info.toLong()
                24 -> uint(1)
                25 -> uint(2)
                26 -> uint(4)
                27 -> uint(8)
                else -> return null // indefinite lengths and reserved values
            } ?: return null
            if (major == 7) return Pair(7, 0) // simple values and floats: payload already consumed
            return Pair(major, arg)
        }

        private fun uint(n: Int): Long? {
            if (at + n > b.size) return null
            var v = 0L
            repeat(n) { v = (v shl 8) or (b[at++].toLong() and 0xFF) }
            return if (n == 8 && v < 0) null else v
        }

        private fun bytes(n: Long): ByteArray? {
            if (n < 0 || n > b.size - at) return null
            return b.copyOfRange(at, at + n.toInt()).also { at += n.toInt() }
        }
    }
}
