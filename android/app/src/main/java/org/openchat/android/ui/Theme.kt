package org.openchat.android.ui

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Dark palette from docs/design/ui-prototype.html: signal orange on near-black.
private val Signal = Color(0xFFFF7840)
private val SignalSoft = Color(0xFF3A1A0C)

/** Dark by default, whatever the system setting. */
@Composable
fun OpenChatTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Signal, onPrimary = Color(0xFF1A0A03),
        primaryContainer = SignalSoft, onPrimaryContainer = Color(0xFFEDF1EF),
        secondary = Signal, onSecondary = Color(0xFF1A0A03),
        secondaryContainer = SignalSoft, onSecondaryContainer = Signal,
        background = Color(0xFF0B0D0D), onBackground = Color(0xFFEDF1EF),
        surface = Color(0xFF0B0D0D), onSurface = Color(0xFFEDF1EF),
        surfaceVariant = Color(0xFF121616), onSurfaceVariant = Color(0xFF9AA4A3),
        outline = Color(0xFF4A5555), outlineVariant = Color(0xFF262D2D),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
internal fun plural(@PluralsRes id: Int, count: Int): String = LocalResources.current.getQuantityString(id, count, count)

/**
 * The language the app is showing. It is the in-app choice (AppLanguage wraps the activity's
 * context), which need not be the system's, so dates, times and numbers must be formatted with
 * this and never with Locale.getDefault(): that one still says what the system is set to.
 */
@Composable
internal fun appLocale(): java.util.Locale = LocalConfiguration.current.locales[0]
