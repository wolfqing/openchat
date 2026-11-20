package org.openchat.android.ui

import androidx.annotation.PluralsRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openchat.android.app.AppTheme

@Immutable
data class OpenChatColors(
    val isLight: Boolean,
    val screen: Color,
    val surface: Color,
    val card: Color,
    val cardRaised: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val outline: Color,
    val signal: Color,
    val signalSoft: Color,
    val onSignal: Color,
    val lock: Color,
    val lockSoft: Color,
    val online: Color,
)

internal val DarkOpenChatColors = OpenChatColors(
    isLight = false,
    screen = Color(0xFF000000),
    surface = Color(0xFF121211),
    card = Color(0xFF1A1A18),
    cardRaised = Color(0xFF232220),
    ink = Color(0xFFF2F0EC),
    muted = Color(0xFF9D9A93),
    line = Color(0xFF262522),
    outline = Color(0xFF3E3C38),
    signal = Color(0xFFFF682B),
    signalSoft = Color(0xFF2E150A),
    onSignal = Color(0xFFFFFFFF),
    lock = Color(0xFF5CA6FF),
    lockSoft = Color(0xFF13263A),
    online = Color(0xFF2EB872),
)

internal val LightOpenChatColors = OpenChatColors(
    isLight = true,
    screen = Color(0xFFF4F2EC),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFEFECE5),
    cardRaised = Color(0xFFE6E2D9),
    ink = Color(0xFF171614),
    muted = Color(0xFF646058),
    line = Color(0xFFE3DFD6),
    outline = Color(0xFFC8C2B6),
    signal = Color(0xFFFF5A1F),
    signalSoft = Color(0xFFFFECE4),
    onSignal = Color(0xFFFFFFFF),
    lock = Color(0xFF1A6BFF),
    lockSoft = Color(0xFFDCEBFF),
    online = Color(0xFF129A5A),
)

internal val LocalOpenChatColors = staticCompositionLocalOf { DarkOpenChatColors }

/**
 * True inside elevated white/card containers (settings cards, bottom sheets, drawers) so
 * borderless controls placed inside them switch from floating white to warm-stone tonal fills.
 */
internal val LocalOnElevatedSurface = compositionLocalOf { false }

/**
 * Semantic tokens from docs/design/ui-direction.md and docs/design/ui-prototype.html,
 * supporting both Warm Stone Light (daylight legibility) and OLED Dark (blackout battery saving).
 *
 * Two strict layers:
 * - Content & chrome stay warm-neutral and quiet: warm stone paper or pure OLED black ground,
 *   crisp high-contrast ink, pure white floating chrome on canvas and warm tonal wells inside cards.
 * - Signal orange ([Signal]) belongs strictly to the mesh/network layer (signal band, hops,
 *   delivery path, live PTT, radar, globe crosshair, unread badges, @mentions).
 * - Lock blue ([Lock] / [LockSoft]) marks end-to-end encrypted conversations exclusively.
 */
object OpenChatPalette {
    val isLight: Boolean @Composable get() = LocalOpenChatColors.current.isLight
    val Screen: Color @Composable get() = LocalOpenChatColors.current.screen
    val Surface: Color @Composable get() = LocalOpenChatColors.current.surface
    val Card: Color @Composable get() = LocalOpenChatColors.current.card
    val CardRaised: Color @Composable get() = LocalOpenChatColors.current.cardRaised
    val Ink: Color @Composable get() = LocalOpenChatColors.current.ink
    val Muted: Color @Composable get() = LocalOpenChatColors.current.muted
    val Line: Color @Composable get() = LocalOpenChatColors.current.line
    val Outline: Color @Composable get() = LocalOpenChatColors.current.outline

    /** Elevated card/sheet/drawer background (pure white in light mode, warm charcoal in OLED dark). */
    val CardSurface: Color
        @Composable get() = if (isLight) Surface else Color(0xFF141413)

    /**
     * Borderless fill for interactive pills, status capsules, and circular controls:
     * pure white floating over the warm-stone canvas in light mode, automatically stepping to
     * warm-stone tonal fill ([Card]) when placed inside an elevated card, sheet, or drawer.
     */
    val Control: Color
        @Composable get() = when {
            LocalOnElevatedSurface.current && isLight -> Card
            LocalOnElevatedSurface.current -> CardRaised
            isLight -> Surface
            else -> Card
        }

    /** Inset tonal well inside cards/sheets (fingerprint grid, relay rows, segmented tracks, list containers). */
    val Well: Color
        @Composable get() = if (isLight) Color(0xFFF2F0EA) else Color(0xFF1B1B19)

    /** Passive non-interactive badges & dividers on canvas (day headers, system notices, public chip). */
    val ChipPassive: Color
        @Composable get() = if (isLight) Color(0xFFEAE7DF) else Color(0xFF161615)

    val Signal: Color @Composable get() = LocalOpenChatColors.current.signal
    val SignalSoft: Color @Composable get() = LocalOpenChatColors.current.signalSoft
    val OnSignal: Color @Composable get() = LocalOpenChatColors.current.onSignal

    val Lock: Color @Composable get() = LocalOpenChatColors.current.lock
    val LockSoft: Color @Composable get() = LocalOpenChatColors.current.lockSoft

    val Online: Color @Composable get() = LocalOpenChatColors.current.online
}

internal val TabularMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
)

private val OpenChatTypography = Typography(
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
)

private val OpenChatDarkScheme = darkColorScheme(
    primary = DarkOpenChatColors.signal,
    onPrimary = Color.White,
    primaryContainer = DarkOpenChatColors.lockSoft,
    onPrimaryContainer = DarkOpenChatColors.ink,
    secondary = Color(0xFF2B2A27),
    onSecondary = DarkOpenChatColors.ink,
    secondaryContainer = DarkOpenChatColors.cardRaised,
    onSecondaryContainer = DarkOpenChatColors.ink,
    tertiary = DarkOpenChatColors.lock,
    onTertiary = Color.White,
    tertiaryContainer = DarkOpenChatColors.signalSoft,
    onTertiaryContainer = DarkOpenChatColors.ink,
    background = DarkOpenChatColors.screen,
    onBackground = DarkOpenChatColors.ink,
    surface = DarkOpenChatColors.screen,
    onSurface = DarkOpenChatColors.ink,
    surfaceVariant = DarkOpenChatColors.card,
    onSurfaceVariant = DarkOpenChatColors.muted,
    surfaceContainerLowest = DarkOpenChatColors.screen,
    surfaceContainerLow = DarkOpenChatColors.surface,
    surfaceContainer = DarkOpenChatColors.surface,
    surfaceContainerHigh = DarkOpenChatColors.card,
    surfaceContainerHighest = DarkOpenChatColors.cardRaised,
    outline = DarkOpenChatColors.outline,
    outlineVariant = DarkOpenChatColors.line,
    error = Color(0xFFFF8970),
    onError = Color(0xFF2B0600),
    errorContainer = Color(0xFF3B120B),
    onErrorContainer = Color(0xFFFFDAD2),
)

private val OpenChatLightScheme = lightColorScheme(
    primary = LightOpenChatColors.signal,
    onPrimary = Color.White,
    primaryContainer = LightOpenChatColors.lockSoft,
    onPrimaryContainer = LightOpenChatColors.ink,
    secondary = LightOpenChatColors.ink,
    onSecondary = Color.White,
    secondaryContainer = LightOpenChatColors.cardRaised,
    onSecondaryContainer = LightOpenChatColors.ink,
    tertiary = LightOpenChatColors.lock,
    onTertiary = Color.White,
    tertiaryContainer = LightOpenChatColors.signalSoft,
    onTertiaryContainer = LightOpenChatColors.ink,
    background = LightOpenChatColors.screen,
    onBackground = LightOpenChatColors.ink,
    surface = LightOpenChatColors.surface,
    onSurface = LightOpenChatColors.ink,
    surfaceVariant = LightOpenChatColors.card,
    onSurfaceVariant = LightOpenChatColors.muted,
    surfaceContainerLowest = LightOpenChatColors.surface,
    surfaceContainerLow = LightOpenChatColors.screen,
    surfaceContainer = LightOpenChatColors.card,
    surfaceContainerHigh = LightOpenChatColors.card,
    surfaceContainerHighest = LightOpenChatColors.cardRaised,
    outline = LightOpenChatColors.outline,
    outlineVariant = LightOpenChatColors.line,
    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
)

@Composable
fun OpenChatTheme(content: @Composable () -> Unit) {
    val choice by AppTheme.choiceFlow.collectAsState()
    val dark = AppTheme.resolveIsDark(choice, isSystemInDarkTheme())
    val palette = if (dark) DarkOpenChatColors else LightOpenChatColors
    val scheme = if (dark) OpenChatDarkScheme else OpenChatLightScheme
    CompositionLocalProvider(
        LocalOpenChatColors provides palette,
        LocalContentColor provides palette.ink,
    ) {
        MaterialTheme(colorScheme = scheme, typography = OpenChatTypography) {
            CompositionLocalProvider(LocalContentColor provides palette.ink, content = content)
        }
    }
}

/** Subtle spring press-scale feedback for interactive Liquid Glass pills and buttons. */
internal fun Modifier.pressScale(pressedScale: Float = 0.94f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press-scale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                pressed = false
            }
        }
}

/**
 * Atmospheric canvas background:
 * - Warm Stone Light: `#F6F4EE -> #F4F2EC -> #EDEAE2` warm daylight paper with a subtle
 *   top-left warm Signal-orange glow and zero blue wash so pure-white floating pills and
 *   cards separate crisply without borders.
 * - OLED Dark: pure `#000000` canvas with an ultra-subtle top-left warm glow.
 */
internal fun Modifier.openChatCanvas(): Modifier = composed {
    val isLight = OpenChatPalette.isLight
    this.drawWithCache {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val topGlowRadius = maxOf(w * 0.88f, h * 0.36f).coerceAtLeast(2f)
        if (isLight) {
            val baseBrush = Brush.verticalGradient(
                0.00f to Color(0xFFF6F4EE),
                0.55f to Color(0xFFF4F2EC),
                1.00f to Color(0xFFEDEAE2),
            )
            val topGlow = Brush.radialGradient(
                colors = listOf(Color(0xFFFF6A30).copy(alpha = 0.045f), Color.Transparent),
                center = Offset(w * 0.16f, h * 0.07f),
                radius = topGlowRadius,
            )
            onDrawBehind {
                drawRect(brush = baseBrush)
                drawRect(brush = topGlow)
            }
        } else {
            val topGlow = Brush.radialGradient(
                colors = listOf(Color(0xFFFF7840).copy(alpha = 0.065f), Color.Transparent),
                center = Offset(w * 0.20f, h * 0.06f),
                radius = topGlowRadius,
            )
            onDrawBehind {
                drawRect(color = Color.Black)
                drawRect(brush = topGlow)
            }
        }
    }
}

/** 0.75dp low-contrast separator matching `--line` in the UI prototype. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    val isLight = OpenChatPalette.isLight
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.75.dp,
        color = if (isLight) Color(0xFF171614).copy(alpha = 0.08f) else Color.White.copy(alpha = 0.07f),
    )
}

/** Unread count pill in signal orange, reserved for genuine network activity. */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Badge(
        modifier = modifier.defaultMinSize(minWidth = 18.dp),
        containerColor = OpenChatPalette.Signal,
        contentColor = Color.White,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = TabularMonoStyle,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun chromeTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
    navigationIconContentColor = OpenChatPalette.Ink,
    titleContentColor = OpenChatPalette.Ink,
    actionIconContentColor = OpenChatPalette.Ink,
)

internal val NavContainerColor: Color @Composable get() = OpenChatPalette.Surface
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
