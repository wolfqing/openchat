package org.openchat.android.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language choice. It is applied by wrapping each Activity and Service base context,
 * which works on every API level without AppCompat. A panic wipe leaves it alone: it says
 * nothing about the user's identity. With nothing saved the app starts in English.
 */
object AppLanguage {
    enum class Choice(val tag: String?) { SYSTEM(null), ZH("zh"), EN("en") }

    private const val PREFS = "ui"
    private const val KEY = "language"

    fun get(context: Context): Choice {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Choice.entries.firstOrNull { it.name == saved } ?: Choice.EN
    }

    fun set(context: Context, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.name).apply()
    }

    fun wrap(base: Context): Context {
        val tag = get(base).tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
