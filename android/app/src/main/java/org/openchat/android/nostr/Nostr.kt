package org.openchat.android.nostr

import org.openchat.android.core.hexToBytes
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex
import org.openchat.android.crypto.Crypto
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Geohash encoding (base32 "0123456789bcdefghjkmnpqrstuvwxyz"). */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Channel levels from docs/protocol-spec.md §9. The UI names them from string resources. */
    enum class Level(val precision: Int) {
        BLOCK(7), NEIGHBORHOOD(6), CITY(5), PROVINCE(4), REGION(2)
    }

    fun encode(lat: Double, lon: Double, precision: Int): String {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        val sb = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonLo = mid } else lonHi = mid
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latLo = mid } else latHi = mid
            }
            even = !even
            if (bit < 4) bit++ else { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    /** Centre of a geohash cell as (lat, lon), or null for invalid input. */
    fun center(hash: String): Pair<Double, Double>? {
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        var even = true
        for (c in hash.lowercase()) {
            val v = BASE32.indexOf(c)
            if (v < 0) return null
            for (b in 4 downTo 0) {
                val on = (v shr b) and 1 == 1
                if (even) { val mid = (lonLo + lonHi) / 2; if (on) lonLo = mid else lonHi = mid }
                else { val mid = (latLo + latHi) / 2; if (on) latLo = mid else latHi = mid }
                even = !even
            }
        }
        return (latLo + latHi) / 2 to (lonLo + lonHi) / 2
    }

    fun isValid(hash: String) = hash.isNotEmpty() && hash.length <= 12 && hash.all { it.lowercaseChar() in BASE32 }
}

/** Nostr keypair: 32-byte secret, x-only public key. */
class NostrKey(val secret: ByteArray) {
    val publicKeyHex: String = Schnorr.publicKey(secret).toHex()

    companion object {
        /**
         * Per-geohash identity (§9): HMAC-SHA256(seed, geohash ‖ u32be(i)) for the
         * first i in 0..9 that is a valid scalar, so cells cannot be linked.
         */
        fun forGeohash(seed: ByteArray, geohash: String): NostrKey {
            val msg = geohash.toByteArray(Charsets.UTF_8)
            for (i in 0 until 10) {
                val counter = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
                val k = Crypto.hmacSha256(seed, msg, counter)
                if (Schnorr.isValidSecret(k)) return NostrKey(k)
            }
            return NostrKey(sha256(seed, msg))
        }
    }
}

data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): String = buildString {
        append("{\"id\":\"").append(id).append("\",\"pubkey\":\"").append(pubkey)
        append("\",\"created_at\":").append(createdAt).append(",\"kind\":").append(kind)
        append(",\"tags\":").append(tagsJson(tags)).append(",\"content\":").append(jsonString(content))
        append(",\"sig\":\"").append(sig).append("\"}")
    }

    /** Recomputes the ID and checks the signature. */
    fun isValid(): Boolean {
        val expected = computeId(pubkey, createdAt, kind, tags, content)
        if (expected != id) return false
        return Schnorr.verify(pubkey.hexToBytes() ?: return false, id.hexToBytes() ?: return false, sig.hexToBytes() ?: return false)
    }

    companion object {
        const val KIND_GEOHASH_MESSAGE = 20000
        const val KIND_GEOHASH_PRESENCE = 20001

        fun create(key: NostrKey, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
            val id = computeId(key.publicKeyHex, createdAt, kind, tags, content)
            val sig = Schnorr.sign(key.secret, id.hexToBytes()!!).toHex()
            return NostrEvent(id, key.publicKeyHex, createdAt, kind, tags, content, sig)
        }

        fun geohashMessage(key: NostrKey, geohash: String, nickname: String?, text: String, teleported: Boolean): NostrEvent {
            val tags = mutableListOf(listOf("g", geohash))
            if (!nickname.isNullOrBlank()) tags.add(listOf("n", nickname))
            if (teleported) tags.add(listOf("t", "teleport"))
            return create(key, KIND_GEOHASH_MESSAGE, tags, text)
        }

        fun geohashPresence(key: NostrKey, geohash: String) =
            create(key, KIND_GEOHASH_PRESENCE, listOf(listOf("g", geohash)), "")

        /** NIP-01: sha256 of the compact JSON array [0, pubkey, created_at, kind, tags, content]. */
        fun computeId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
            val serialized = "[0,\"$pubkey\",$createdAt,$kind,${tagsJson(tags)},${jsonString(content)}]"
            return sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun tagsJson(tags: List<List<String>>) = tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { jsonString(it) } }

        /** NIP-01 escaping: only \n \" \\ \r \t \b \f are escaped; other characters are written as-is. */
        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when (c) {
                '\n' -> sb.append("\\n"); '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
                '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t"); '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
            return sb.append('"').toString()
        }

        fun fromJson(o: org.json.JSONObject): NostrEvent? = try {
            val tagsArr = o.getJSONArray("tags")
            val tags = (0 until tagsArr.length()).map { i ->
                val t = tagsArr.getJSONArray(i)
                (0 until t.length()).map { t.getString(it) }
            }
            NostrEvent(o.getString("id"), o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), tags, o.getString("content"), o.getString("sig"))
        } catch (e: Exception) {
            null
        }
    }
}

/** Public relay directory; picks the relays nearest a geohash cell (§9). */
class RelayDirectory(private val relays: List<Triple<String, Double, Double>>) {
    fun closest(geohash: String, count: Int = 5): List<String> {
        val (lat, lon) = Geohash.center(geohash) ?: return emptyList()
        return relays.sortedBy { haversine(lat, lon, it.second, it.third) }.take(count).map { "wss://${it.first}" }
    }

    companion object {
        fun parse(csv: String): RelayDirectory = RelayDirectory(
            csv.lineSequence().drop(1).mapNotNull { line ->
                val parts = line.trim().split(",")
                if (parts.size < 3) null else {
                    val lat = parts[1].toDoubleOrNull(); val lon = parts[2].toDoubleOrNull()
                    if (lat == null || lon == null || parts[0].isBlank()) null
                    else Triple(parts[0].removePrefix("wss://").removePrefix("ws://").trimEnd('/'), lat, lon)
                }
            }.toList(),
        )

        private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.toRadians(1.0)
            val dLat = (lat2 - lat1) * r; val dLon = (lon2 - lon1) * r
            val a = sin(dLat / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(a))
        }
    }
}
