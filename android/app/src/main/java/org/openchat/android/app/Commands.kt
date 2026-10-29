package org.openchat.android.app

/**
 * IRC-style commands typed in a composer, following bitchat's set. Pure: the
 * caller supplies who is around and applies the outcome, so this runs in unit tests.
 */
object Commands {
    /** Someone a command can name. [key] is a peer ID (mesh) or Nostr pubkey (location channel). */
    data class Person(val label: PeerLabel, val key: String)

    enum class Note { HELP, WHO, WHO_NONE, NOT_FOUND, USAGE, UNKNOWN, BLOCKED, UNBLOCKED, NOT_BLOCKED, FAVORITED, UNFAVORITED, MESH_ONLY, DROPPED, NO_LOCATION }

    sealed class Outcome {
        /** Show a local line in this chat; nothing is sent. */
        data class Local(val note: Note, val arg: String = "") : Outcome()
        /** Send this text in the current chat, as if typed. */
        data class Send(val text: String) : Outcome()
        /** Open a private chat, optionally sending a first message. */
        data class OpenPrivate(val peerHex: String, val text: String?) : Outcome()
        object Clear : Outcome()
        data class Block(val person: Person) : Outcome()
        data class Unblock(val name: String) : Outcome()
        data class Favorite(val person: Person, val on: Boolean) : Outcome()
        data class Drop(val text: String) : Outcome()
    }

    /** Commands offered as suggestions, by context. */
    fun available(conversation: String): List<String> {
        val base = listOf("/msg", "/who", "/clear", "/hug", "/slap", "/block", "/unblock", "/help")
        return when {
            conversation.startsWith("geo:") -> base + "/drop"
            else -> base + listOf("/fav", "/unfav")
        }
    }

    fun isCommand(text: String) = text.startsWith("/") && text.length > 1 && !text.startsWith("//")

    fun run(text: String, conversation: String, myName: String, people: List<Person>, dmPeer: Person?): Outcome {
        val cmd = text.substringBefore(' ').lowercase()
        val args = text.substringAfter(' ', "").trim()
        val geo = conversation.startsWith("geo:") || conversation.startsWith("gdm:")
        fun find(name: String): Person? {
            val n = name.removePrefix("@").lowercase()
            if (n.isEmpty()) return null
            return people.firstOrNull { it.label.toString().lowercase() == n }
                ?: people.filter { it.label.name.lowercase() == n }.singleOrNull()
        }
        val targetName = args.substringBefore(' ')
        return when (cmd) {
            "/help" -> Outcome.Local(Note.HELP)
            "/who", "/w" -> if (people.isEmpty()) Outcome.Local(Note.WHO_NONE) else Outcome.Local(Note.WHO, people.joinToString(", ") { it.label.toString() })
            "/clear" -> Outcome.Clear
            "/msg", "/m" -> {
                if (geo) return Outcome.Local(Note.MESH_ONLY)
                val p = find(targetName) ?: return if (targetName.isEmpty()) Outcome.Local(Note.USAGE, "/msg @name [message]") else Outcome.Local(Note.NOT_FOUND, targetName)
                Outcome.OpenPrivate(p.key, args.substringAfter(' ', "").trim().ifEmpty { null })
            }
            "/hug", "/slap" -> {
                val p = find(targetName) ?: dmPeer?.takeIf { targetName.isEmpty() }
                    ?: return if (targetName.isEmpty()) Outcome.Local(Note.USAGE, "$cmd @name") else Outcome.Local(Note.NOT_FOUND, targetName)
                val (emoji, verb, tail) = if (cmd == "/hug") Triple("🫂", "hugs", "") else Triple("🐟", "slaps", " around a bit with a large trout")
                val who = if (dmPeer != null) "you" else p.label.name
                Outcome.Send("* $emoji $myName $verb $who$tail *")
            }
            "/block" -> {
                val p = find(targetName) ?: dmPeer?.takeIf { targetName.isEmpty() }
                    ?: return if (targetName.isEmpty()) Outcome.Local(Note.USAGE, "/block @name") else Outcome.Local(Note.NOT_FOUND, targetName)
                Outcome.Block(p)
            }
            "/unblock" -> if (targetName.isEmpty()) Outcome.Local(Note.USAGE, "/unblock @name") else Outcome.Unblock(targetName.removePrefix("@"))
            "/fav", "/unfav" -> {
                if (geo) return Outcome.Local(Note.MESH_ONLY)
                val p = find(targetName) ?: dmPeer?.takeIf { targetName.isEmpty() }
                    ?: return if (targetName.isEmpty()) Outcome.Local(Note.USAGE, "$cmd @name") else Outcome.Local(Note.NOT_FOUND, targetName)
                Outcome.Favorite(p, cmd == "/fav")
            }
            "/drop" -> if (args.isEmpty()) Outcome.Local(Note.USAGE, "/drop message") else Outcome.Drop(args)
            else -> Outcome.Local(Note.UNKNOWN, cmd)
        }
    }
}
