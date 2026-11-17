package org.openchat.android.app

import org.openchat.android.mesh.Identity
import java.util.Locale
import kotlin.random.Random

/**
 * Curated random callsigns without trailing digits or `#`, guaranteeing 100% compatibility
 * with [PeerLabel]'s `#xxxx` suffix formatting. Used for initial identity generation,
 * legacy `"anon"` upgrade migration, panic-wipe replacement, and the "Random" button in Me.
 */
object RandomNicknames {
    val ZH_HANS = listOf(
        "张衡", "李白", "苏轼", "沈括", "祖冲之", "徐霞客", "墨子", "鲁班",
        "王维", "清照", "辛弃疾", "庄周", "陶潜", "山风", "星河", "远帆",
        "极光", "灯塔", "青松", "信风", "晨光", "云雀", "北辰", "海鸥",
        "林溪", "微光", "晴空", "雪松", "破晓", "流萤", "观澜", "飞鸟",
        "白桦", "苍穹", "海风", "听雨", "追风", "银杏", "青岚", "惊蛰",
        "星野", "归鸿", "松风", "浮光", "长风", "暮雪", "新月", "清泉",
    )

    val ZH_HANT = listOf(
        "張衡", "李白", "蘇軾", "沈括", "祖沖之", "徐霞客", "墨子", "魯班",
        "王維", "清照", "辛棄疾", "莊周", "陶潛", "山風", "星河", "遠帆",
        "極光", "燈塔", "青松", "信風", "晨光", "雲雀", "北辰", "海鷗",
        "林溪", "微光", "晴空", "雪松", "破曉", "流螢", "觀瀾", "飛鳥",
        "白樺", "蒼穹", "海風", "聽雨", "追風", "銀杏", "青嵐", "驚蟄",
        "星野", "歸鴻", "松風", "浮光", "長風", "暮雪", "新月", "清泉",
    )

    val LATIN = listOf(
        "Lovelace", "Darwin", "Curie", "Faraday", "Hypatia", "Turing", "Noether", "Kepler",
        "Tesla", "Maxwell", "Halley", "Euler", "Gauss", "Hopper", "Shannon", "Galileo",
        "Newton", "Archimedes", "Feynman", "Hawking", "Falcon", "Beacon", "Aurora", "Zephyr",
        "Solstice", "Horizon", "Orion", "Cedar", "Summit", "Harbor", "Kestrel", "Mariner",
        "Nimbus", "Quartz", "Raven", "Spruce", "Swift", "Zenith", "Compass", "Vesper",
        "Canyon", "Glacier", "Sierra", "Juniper", "Cobalt", "Ember", "Lumen", "Voyager",
    )

    fun poolFor(locale: Locale): List<String> = when {
        locale.language.equals("zh", ignoreCase = true) &&
            (locale.script.equals("Hant", ignoreCase = true) || locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")) -> ZH_HANT
        locale.language.equals("zh", ignoreCase = true) -> ZH_HANS
        else -> LATIN
    }

    fun next(current: String = "", locale: Locale = Locale.getDefault(), random: Random = Random.Default): String {
        val pool = poolFor(locale)
        val trimmed = current.trim()
        val candidates = pool.filter { !it.equals(trimmed, ignoreCase = true) }
        val source = candidates.ifEmpty { pool }
        return source[random.nextInt(source.size)]
    }

    /**
     * Replaces a blank nickname or a legacy default `"anon"` (once, before [alreadyMigrated] is set)
     * with a random callsign while keeping the identity's keys, peerId and fingerprint intact.
     */
    fun migrateIfDefaultAnon(
        identity: Identity,
        alreadyMigrated: Boolean,
        locale: Locale = Locale.getDefault(),
        random: Random = Random.Default,
    ): Identity? {
        val trimmed = identity.nickname.trim()
        val shouldReplace = trimmed.isEmpty() || (!alreadyMigrated && trimmed.equals("anon", ignoreCase = true))
        if (!shouldReplace) return null
        return identity.withNickname(next(trimmed, locale, random))
    }
}
