package org.openchat.android.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app appearance preference: follows system day/night by default, with one-tap override
 * between Radiant Light (outdoor legibility) and pure-black OLED Dark (blackout battery saving).
 * Stored alongside [AppLanguage] in `ui` preferences and updated reactively without recreating
 * the Activity.
 */
object AppTheme {
    enum class Choice(val code: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark"),
    }

    private const val PREFS = "ui"
    private const val KEY = "theme"

    @Volatile private var loaded = false
    private val _choice = MutableStateFlow(Choice.SYSTEM)
    val choiceFlow: StateFlow<Choice> = _choice.asStateFlow()

    internal fun fromCode(code: String?): Choice =
        Choice.entries.firstOrNull { it.code == code } ?: Choice.SYSTEM

    internal fun resolveIsDark(choice: Choice, systemIsDark: Boolean): Boolean = when (choice) {
        Choice.DARK -> true
        Choice.LIGHT -> false
        Choice.SYSTEM -> systemIsDark
    }

    fun load(context: Context): Choice {
        val c = fromCode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
        _choice.value = c
        loaded = true
        return c
    }

    fun choice(context: Context): Choice = if (loaded) _choice.value else load(context)

    fun set(context: Context, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, choice.code)
            .apply()
        loaded = true
        _choice.value = choice
    }
}
