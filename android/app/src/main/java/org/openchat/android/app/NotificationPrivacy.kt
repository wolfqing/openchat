package org.openchat.android.app

import android.content.Context

/**
 * Whether notifications may show who wrote and what (docs/protocol-spec.md §21.3).
 * Off by default: a locked phone left on a table, or taken from someone, should
 * not read their conversations out. With it off a notification only says that
 * something arrived.
 */
object NotificationPrivacy {
    private const val PREFS = "notify"
    private const val KEY = "show_previews"

    fun showPreviews(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setShowPreviews(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    /** Panic wipe: back to the quiet default, so a wiped phone is never louder than a new one. */
    fun reset(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
