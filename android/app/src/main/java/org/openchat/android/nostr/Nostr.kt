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

    /** A cell's edges in degrees. */
    data class Bounds(val latLo: Double, val latHi: Double, val lonLo: Double, val lonHi: Double)

    /** Edges of a geohash cell, or null for invalid input. */
    fun bounds(hash: String): Bounds? {
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
        return Bounds(latLo, latHi, lonLo, lonHi)
    }

    /** Centre of a geohash cell as (lat, lon), or null for invalid input. */
    fun center(hash: String): Pair<Double, Double>? =
        bounds(hash)?.let { (it.latLo + it.latHi) / 2 to (it.lonLo + it.lonHi) / 2 }

    /** Width and height of a cell at [precision] in degrees: bits alternate, longitude first. */
    fun cellSize(precision: Int): Pair<Double, Double> {
        val bits = precision * 5
        return 360.0 / (1L shl ((bits + 1) / 2)).toDouble() to 180.0 / (1L shl (bits / 2)).toDouble()
    }

    fun isValid(hash: String) = hash.isNotEmpty() && hash.length <= 12 && hash.all { it.lowercaseChar() in BASE32 }

    /** The eight cells around [hash] at the same precision (fewer at the poles). */
    fun neighbors(hash: String): List<String> {
        val (lat, lon) = center(hash) ?: return emptyList()
        val (dLon, dLat) = cellSize(hash.length)
        val out = LinkedHashSet<String>()
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val la = lat + dy * dLat
            if (la <= -90 || la >= 90) continue
            var lo = lon + dx * dLon
            if (lo >= 180) lo -= 360; if (lo < -180) lo += 360
            out.add(encode(la, lo, hash.length))
        }
        out.remove(hash)
        return out.toList()
    }
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
        const val KIND_TEXT_NOTE = 1
        const val KIND_GEOHASH_MESSAGE = 20000
        const val KIND_GEOHASH_PRESENCE = 20001

        fun create(key: NostrKey, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent {
            val id = computeId(key.publicKeyHex, createdAt, kind, tags, content)
            val sig = Schnorr.sign(key.secret, id.hexToBytes()!!).toHex()
            return NostrEvent(id, key.publicKeyHex, createdAt, kind, tags, content, sig)
        }

        fun geohashMessage(key: NostrKey, geohash: String, nickname: String?, text: String, teleported: Boolean, mine: Boolean = false): NostrEvent {
            val tags = mutableListOf(listOf("g", geohash))
            if (!nickname.isNullOrBlank()) tags.add(listOf("n", nickname))
            if (teleported) tags.add(listOf("t", "teleport"))
            val createdAt = System.currentTimeMillis() / 1000
            if (mine) Pow.mine(key.publicKeyHex, createdAt, KIND_GEOHASH_MESSAGE, tags, text)?.let { tags.add(it) }
            return create(key, KIND_GEOHASH_MESSAGE, tags, text, createdAt)
        }

        /** A location note (§17): kind 1, at building precision, fading after [ttlS]. */
        fun locationNote(key: NostrKey, geohash: String, nickname: String?, text: String, nowS: Long, ttlS: Long = 86_400): NostrEvent {
            val tags = mutableListOf(listOf("g", geohash))
            if (!nickname.isNullOrBlank()) tags.add(listOf("n", nickname))
            tags.add(listOf("expiration", (nowS + ttlS).toString()))
            return create(key, KIND_TEXT_NOTE, tags, text, nowS)
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

/**
 * Public relay directory; picks the relays nearest a geohash cell (§9).
 * Strips default `:443` ports, deduplicates hosts, and breaks distance ties
 * lexicographically by host so clients with the same directory converge on
 * identical relay sets.
 */
class RelayDirectory(initialEntries: List<Entry>) {
    data class Entry(val host: String, val lat: Double, val lon: Double)

    @Volatile private var entries: List<Entry> = initialEntries
    @Volatile var onUpdated: () -> Unit = {}

    val size: Int get() = entries.size

    fun entriesSnapshot(): List<Entry> = entries

    fun updateEntries(next: List<Entry>) {
        if (next.isEmpty() || next == entries) return
        entries = next
        onUpdated()
    }

    fun closest(geohash: String, count: Int = 5): List<String> {
        val (lat, lon) = Geohash.center(geohash) ?: return emptyList()
        val snapshot = entries
        if (snapshot.isEmpty() || count <= 0) return emptyList()
        return snapshot
            .map { it to haversine(lat, lon, it.lat, it.lon) }
            .sortedWith(Comparator { a, b ->
                val d = a.second.compareTo(b.second)
                if (d != 0) d else a.first.host.compareTo(b.first.host)
            })
            .take(count)
            .map { "wss://${it.first.host}" }
    }

    companion object {
        private val ENTRY_COMPARATOR = compareBy<Entry>({ it.host }, { it.lat }, { it.lon })
        private val SUPPORTED_HEADERS = setOf(
            listOf("relay url", "latitude", "longitude"),
            listOf("relay url", "lat", "lon"),
        )

        fun parse(csv: String): RelayDirectory {
            val byHost = LinkedHashMap<String, Entry>()
            csv.lineSequence().drop(1).forEach { line ->
                val parts = line.trim().split(",")
                if (parts.size >= 3) {
                    val host = normalizeAddress(parts[0])
                    val lat = parts[1].trim().toDoubleOrNull()
                    val lon = parts[2].trim().toDoubleOrNull()
                    if (host != null && lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        byHost[host] = Entry(host, lat, lon)
                    }
                }
            }
            return RelayDirectory(byHost.values.sortedWith(ENTRY_COMPARATOR))
        }

        /**
         * Strict all-or-nothing validation for cached or remote directory refreshes:
         * rejects malformed rows, conflicting coordinates for the same host, undersized
         * snapshots, or snapshots that retain fewer than 50% of [baseline] hosts.
         */
        fun parseValidatedEntries(
            csv: String,
            minimumEntries: Int = 50,
            baseline: List<Entry>? = null,
            maxBytes: Int = 512 * 1024,
            maxRows: Int = 5_000,
        ): List<Entry>? {
            if (csv.isEmpty() || csv.startsWith("\uFEFF")) return null
            if (csv.toByteArray(Charsets.UTF_8).size > maxBytes) return null
            val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.isEmpty() || lines.size - 1 > maxRows) return null
            val header = lines.first().split(",").map { it.trim().lowercase(java.util.Locale.ROOT) }
            if (header !in SUPPORTED_HEADERS) return null

            val byHost = LinkedHashMap<String, Entry>()
            for (line in lines.drop(1)) {
                val parts = line.split(",").map { it.trim() }
                if (parts.size != 3) return null
                val host = normalizeAddress(parts[0]) ?: return null
                val lat = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
                val lon = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
                val entry = Entry(host, lat, lon)
                val existing = byHost[host]
                if (existing != null && existing != entry) return null
                byHost[host] = entry
                if (byHost.size > maxRows) return null
            }

            if (byHost.size < minimumEntries) return null
            if (!baseline.isNullOrEmpty()) {
                val baseHosts = baseline.mapTo(HashSet()) { it.host }
                val requiredOverlap = kotlin.math.ceil(baseHosts.size * 0.5).toInt()
                val overlap = byHost.keys.count { it in baseHosts }
                if (overlap < requiredOverlap) return null
            }
            return byHost.values.sortedWith(ENTRY_COMPARATOR)
        }

        internal fun normalizeAddress(raw: String): String? {
            val v = raw.trim()
            if (v.isEmpty() || v.any { it.code <= 0x20 || it.code >= 0x7F }) return null
            val rest = when {
                v.startsWith("wss://", ignoreCase = true) -> v.substring(6)
                v.startsWith("https://", ignoreCase = true) -> v.substring(8)
                "://" in v -> return null
                else -> v
            }
            val authority = if (rest.endsWith('/')) rest.dropLast(1) else rest
            if (authority.isEmpty() || authority.any { it == '/' || it == '?' || it == '#' || it == '@' }) return null

            val colonIdx = authority.lastIndexOf(':')
            val rawHost: String
            val port: Int?
            if (colonIdx >= 0) {
                if (authority.indexOf(':') != colonIdx) return null
                rawHost = authority.substring(0, colonIdx)
                val p = authority.substring(colonIdx + 1).toIntOrNull() ?: return null
                if (p !in 1..65_535) return null
                port = p
            } else {
                rawHost = authority
                port = null
            }

            val host = rawHost.lowercase(java.util.Locale.ROOT)
            if (host.isEmpty() || host.length > 253 || host.endsWith('.')) return null
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null
            val labels = host.split('.')
            if (labels.size < 2 || labels.all { lbl -> lbl.isNotEmpty() && lbl.all { it in '0'..'9' } }) return null
            for (lbl in labels) {
                if (lbl.length !in 1..63 || lbl.first() == '-' || lbl.last() == '-') return null
                if (!lbl.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
            }
            return if (port != null && port != 443) "$host:$port" else host
        }

        private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.toRadians(1.0)
            val dLat = (lat2 - lat1) * r; val dLon = (lon2 - lon1) * r
            val a = sin(dLat / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2).pow(2)
            return 2 * 6371.0 * asin(sqrt(a))
        }
    }
}
