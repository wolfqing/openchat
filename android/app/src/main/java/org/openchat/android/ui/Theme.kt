package org.openchat.android.ui

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic tokens from docs/design/ui-direction.md and docs/design/ui-prototype.html.
 *
 * Two strict layers:
 * - Content & chrome stay quiet: pure OLED black ground, dark slate chrome, crisp off-white ink.
 * - Signal orange ([Signal]) belongs strictly to the mesh/network layer (signal band, hops,
 *   delivery path, radar, globe crosshair, unread badges, @mentions).
 * - Lock blue ([Lock] / [LockSoft]) marks end-to-end encrypted conversations.
 */
object OpenChatPalette {
    val Screen = Color(0xFF000000)
    val Surface = Color(0xFF101414)
    val Card = Color(0xFF151B1B)
    val CardRaised = Color(0xFF1C2424)
    val Ink = Color(0xFFEDF1EF)
    val Muted = Color(0xFF9AA4A3)
    val Line = Color(0xFF222929)
    val Outline = Color(0xFF384343)

    val Signal = Color(0xFFFF7840)
    val SignalSoft = Color(0xFF2E150A)
    val OnSignal = Color(0xFF1A0A03)

    val Lock = Color(0xFF93BCE8)
    val LockSoft = Color(0xFF13263A)

    val Online = Color(0xFF2EB872)
}

private val OpenChatTypography = Typography(
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace),
)

/** Dark by default, whatever the system setting: pure black saves OLED battery during blackouts. */
@Composable
fun OpenChatTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = OpenChatPalette.Ink,
        onPrimary = Color(0xFF0B0E0E),
        primaryContainer = OpenChatPalette.LockSoft,
        onPrimaryContainer = OpenChatPalette.Ink,
        secondary = OpenChatPalette.Ink,
        onSecondary = Color(0xFF0B0E0E),
        secondaryContainer = OpenChatPalette.CardRaised,
        onSecondaryContainer = OpenChatPalette.Ink,
        tertiary = OpenChatPalette.Signal,
        onTertiary = OpenChatPalette.OnSignal,
        tertiaryContainer = OpenChatPalette.SignalSoft,
        onTertiaryContainer = OpenChatPalette.Ink,
        background = OpenChatPalette.Screen,
        onBackground = OpenChatPalette.Ink,
        surface = OpenChatPalette.Screen,
        onSurface = OpenChatPalette.Ink,
        surfaceVariant = OpenChatPalette.Card,
        onSurfaceVariant = OpenChatPalette.Muted,
        surfaceContainerLowest = OpenChatPalette.Screen,
        surfaceContainerLow = OpenChatPalette.Surface,
        surfaceContainer = OpenChatPalette.Surface,
        surfaceContainerHigh = OpenChatPalette.Card,
        surfaceContainerHighest = OpenChatPalette.CardRaised,
        outline = OpenChatPalette.Outline,
        outlineVariant = OpenChatPalette.Line,
        error = Color(0xFFFF8970),
        onError = Color(0xFF2B0600),
        errorContainer = Color(0xFF3B120B),
        onErrorContainer = Color(0xFFFFDAD2),
    )
    MaterialTheme(colorScheme = scheme, typography = OpenChatTypography, content = content)
}

/** 1dp low-contrast separator matching `--line` in the UI prototype. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Unread count pill in signal orange, reserved for genuine network activity. */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Badge(
        modifier = modifier.defaultMinSize(minWidth = 18.dp),
        containerColor = OpenChatPalette.Signal,
        contentColor = OpenChatPalette.OnSignal,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun chromeTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = OpenChatPalette.Surface,
    scrolledContainerColor = OpenChatPalette.Surface,
    navigationIconContentColor = OpenChatPalette.Ink,
    titleContentColor = OpenChatPalette.Ink,
    actionIconContentColor = OpenChatPalette.Ink,
)

internal val NavContainerColor = OpenChatPalette.Surface
internal val NavTonalElevation get() = NavigationBarDefaults.Elevation * 0

@Composable
internal fun plural(@PluralsRes id: Int, count: Int): String = LocalResources.current.getQuantityString(id, count, count)

/**
 * The language the app is showing. It is the in-app choice (AppLanguage wraps the activity's
 * context), which need not be the system's, so dates, times and numbers must be formatted with
 * this and never with Locale.getDefault(): that one still says what the system is set to.
 */
@Composable
internal fun appLocale(): java.util.Locale = LocalConfiguration.current.locales[0]
