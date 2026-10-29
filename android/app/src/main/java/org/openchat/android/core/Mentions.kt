package org.openchat.android.core

/**
 * Inline @mentions (docs/protocol-spec.md §5): a mention is plain text, "@name"
 * or "@name#abcd" where abcd is the first four hex digits of the peer ID (mesh)
 * or the last four of the Nostr key (location channels).
 */
object Mentions {
    private val TOKEN = Regex("""(?<![\p{L}\p{N}_])@([\p{L}\p{N}_.\-]+(?:#[0-9A-Fa-f]{4})?)""")

    /** Character ranges of every @token, including the @. */
    fun ranges(text: String): List<IntRange> = TOKEN.findAll(text).map { it.range }.toList()

    fun names(text: String): List<String> = TOKEN.findAll(text).map { it.groupValues[1] }.toList()

    /**
     * True if [text] mentions me. When my nickname is [ambiguous] (someone else nearby
     * uses it), only the suffixed form counts, so a namesake's mention is not mine.
     */
    fun mentionsMe(text: String, myNickname: String, mySuffix: String, ambiguous: Boolean): Boolean {
        val nick = myNickname.lowercase()
        val full = "$nick#${mySuffix.lowercase()}"
        return names(text).any { n ->
            val t = n.lowercase()
            t == full || (!ambiguous && t == nick)
        }
    }

    /** The @word being typed at the end of [text], without the @, or null. */
    fun typing(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) return null
        val word = text.substring(at + 1)
        return if (word.all { it.isLetterOrDigit() || it in "_.-#" }) word else null
    }
}
