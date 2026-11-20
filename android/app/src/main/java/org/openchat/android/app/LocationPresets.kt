package org.openchat.android.app

import androidx.annotation.StringRes
import org.openchat.android.R
import org.openchat.android.nostr.Geohash
import java.util.Locale
import java.util.TimeZone

/**
 * Curated 2-character regional geohash channels for one-tap joining and a zero-permission
 * default channel derived purely from the device's system time zone and region.
 */
object LocationPresets {
    data class GeoPreset(
        val geohash: String,
        @StringRes val labelRes: Int,
        val featured: Boolean = true,
    )

    /** 8 high-activity regional rooms shown directly in Quick Join, plus 4 expandable rooms. */
    val ALL: List<GeoPreset> = listOf(
        GeoPreset("dr", R.string.geo_preset_dr, featured = true),
        GeoPreset("9q", R.string.geo_preset_9q, featured = true),
        GeoPreset("wx", R.string.geo_preset_wx, featured = true),
        GeoPreset("wt", R.string.geo_preset_wt, featured = true),
        GeoPreset("ws", R.string.geo_preset_ws, featured = true),
        GeoPreset("xn", R.string.geo_preset_xn, featured = true),
        GeoPreset("gc", R.string.geo_preset_gc, featured = true),
        GeoPreset("u0", R.string.geo_preset_u0, featured = true),
        GeoPreset("wy", R.string.geo_preset_wy, featured = false),
        GeoPreset("u3", R.string.geo_preset_u3, featured = false),
        GeoPreset("w2", R.string.geo_preset_w2, featured = false),
        GeoPreset("r3", R.string.geo_preset_r3, featured = false),
    )

    val FEATURED: List<GeoPreset> = ALL.filter { it.featured }
    val EXTRA: List<GeoPreset> = ALL.filter { !it.featured }

    private val byHash: Map<String, GeoPreset> = ALL.associateBy { it.geohash }

    /** Lookup a curated regional label for [geohash] (exact 2-char match), or null if custom. */
    fun presetFor(geohash: String): GeoPreset? = byHash[geohash.lowercase(Locale.ROOT)]

    /**
     * Picks a sensible default 2-char regional channel without requesting location permission
     * or probing the network: prefers the device's physical time zone, falling back to locale
     * country and finally `#dr`.
     */
    fun defaultPreset(
        locale: Locale = Locale.getDefault(),
        timeZoneId: String = TimeZone.getDefault().id,
    ): GeoPreset {
        val hash = hashForTimeZone(timeZoneId) ?: hashForCountry(locale.country) ?: "dr"
        return byHash[hash] ?: ALL.first()
    }

    private fun hashForTimeZone(tz: String): String? = when {
        tz in US_WEST_TZS -> "9q"
        tz in US_EAST_TZS || tz.startsWith("America/Indiana/") || tz.startsWith("America/Kentucky/") -> "dr"
        tz == "Asia/Urumqi" -> "wx"
        tz in CN_EAST_TZS -> "wt"
        tz in GREATER_BAY_TZS -> "ws"
        tz == "Asia/Tokyo" || tz == "Japan" -> "xn"
        tz == "Asia/Seoul" || tz == "ROK" -> "wy"
        tz in SEA_TZS -> "w2"
        tz in UK_TZS -> "gc"
        tz in WEST_EU_TZS -> "u0"
        tz in CENTRAL_EU_TZS -> "u3"
        tz in AU_NZ_TZS -> "r3"
        else -> null
    }

    private fun hashForCountry(country: String): String? = when (country.uppercase(Locale.ROOT)) {
        "CN" -> "wt"
        "HK", "MO", "TW" -> "ws"
        "US", "CA" -> "dr"
        "JP" -> "xn"
        "KR" -> "wy"
        "GB", "IE" -> "gc"
        "FR", "ES", "PT", "BE", "NL", "LU", "CH" -> "u0"
        "DE", "AT", "PL", "CZ", "IT", "SE", "NO", "DK", "FI", "HU" -> "u3"
        "SG", "MY", "TH", "VN", "ID", "PH" -> "w2"
        "AU", "NZ" -> "r3"
        else -> null
    }

    /** Strips optional leading `#` and invalid characters from manual geohash input. */
    fun sanitizeInput(raw: String): String =
        raw.trim().removePrefix("#").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8)

    /** Serializes persisted joined location channels (`geohash,teleported` per line, capped at 20). */
    fun serializeJoined(entries: Map<String, Boolean>): ByteArray =
        entries.entries
            .asSequence()
            .filter { (g, _) -> Geohash.isValid(g) && g.length >= 2 }
            .take(MAX_SAVED_CHANNELS)
            .joinToString("\n") { (g, teleported) -> "${g.lowercase(Locale.ROOT)},${if (teleported) 1 else 0}" }
            .toByteArray(Charsets.UTF_8)

    /** Parses persisted joined location channels; invalid lines are silently ignored. */
    fun parseJoined(bytes: ByteArray): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        val text = String(bytes, Charsets.UTF_8)
        for (line in text.lineSequence()) {
            if (out.size >= MAX_SAVED_CHANNELS) break
            val parts = line.trim().split(',')
            val g = parts.getOrNull(0)?.lowercase(Locale.ROOT).orEmpty()
            if (!Geohash.isValid(g) || g.length < 2) continue
            val teleported = parts.getOrNull(1) != "0"
            out[g] = teleported
        }
        return out
    }

    private const val MAX_SAVED_CHANNELS = 20

    private val US_WEST_TZS = setOf(
        "America/Los_Angeles", "America/Vancouver", "America/Phoenix", "America/Denver",
        "America/Tijuana", "America/Boise", "US/Pacific", "US/Mountain", "US/Arizona", "PST8PDT", "MST7MDT",
    )
    private val US_EAST_TZS = setOf(
        "America/New_York", "America/Toronto", "America/Detroit", "America/Chicago",
        "America/Montreal", "US/Eastern", "US/Central", "EST5EDT", "CST6CDT",
    )
    private val CN_EAST_TZS = setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "PRC")
    private val GREATER_BAY_TZS = setOf("Asia/Hong_Kong", "Asia/Macau", "Asia/Taipei", "Hongkong", "ROC")
    private val SEA_TZS = setOf(
        "Asia/Singapore", "Asia/Kuala_Lumpur", "Asia/Bangkok", "Asia/Ho_Chi_Minh",
        "Asia/Saigon", "Asia/Jakarta", "Asia/Manila", "Singapore",
    )
    private val UK_TZS = setOf("Europe/London", "Europe/Dublin", "Europe/Belfast", "GB", "Eire")
    private val WEST_EU_TZS = setOf(
        "Europe/Paris", "Europe/Brussels", "Europe/Amsterdam", "Europe/Madrid",
        "Europe/Lisbon", "Europe/Luxembourg", "Europe/Zurich",
    )
    private val CENTRAL_EU_TZS = setOf(
        "Europe/Berlin", "Europe/Vienna", "Europe/Warsaw", "Europe/Prague",
        "Europe/Rome", "Europe/Stockholm", "Europe/Oslo", "Europe/Copenhagen", "Europe/Budapest",
    )
    private val AU_NZ_TZS = setOf(
        "Australia/Sydney", "Australia/Melbourne", "Australia/Brisbane",
        "Australia/Adelaide", "Australia/Perth", "Pacific/Auckland",
    )
}
