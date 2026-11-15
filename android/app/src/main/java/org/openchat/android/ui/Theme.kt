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
    surface = Color(0xFF101414),
    card = Color(0xFF151B1B),
    cardRaised = Color(0xFF1C2424),
    ink = Color(0xFFEDF1EF),
    muted = Color(0xFF9AA4A3),
    line = Color(0xFF222929),
    outline = Color(0xFF384343),
    signal = Color(0xFFFF682B),
    signalSoft = Color(0xFF2E150A),
    onSignal = Color(0xFFFFFFFF),
    lock = Color(0xFF5CA6FF),
    lockSoft = Color(0xFF13263A),
    online = Color(0xFF2EB872),
)

internal val LightOpenChatColors = OpenChatColors(
    isLight = true,
    screen = Color(0xFFF4F7FC),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFEEF2F7),
    cardRaised = Color(0xFFE3EAF3),
    ink = Color(0xFF0E1621),
    muted = Color(0xFF5B6776),
    line = Color(0xFFDCE3EC),
    outline = Color(0xFFBCC7D6),
    signal = Color(0xFFFF5A1F),
    signalSoft = Color(0xFFFFEBE2),
    onSignal = Color(0xFFFFFFFF),
    lock = Color(0xFF1A6BFF),
    lockSoft = Color(0xFFDCEBFF),
    online = Color(0xFF129A5A),
)

internal val LocalOpenChatColors = staticCompositionLocalOf { DarkOpenChatColors }

/**
 * Semantic tokens from docs/design/ui-direction.md and docs/design/ui-prototype.html,
 * supporting both Radiant Light (daylight legibility) and OLED Dark (blackout battery saving).
 *
 * Two strict layers:
 * - Content & chrome stay quiet: clean alabaster or pure OLED black ground, crisp high-contrast ink.
 * - Signal orange ([Signal]) belongs strictly to the mesh/network layer (signal band, hops,
 *   delivery path, live PTT, radar, globe crosshair, unread badges, @mentions).
 * - Lock blue ([Lock] / [LockSoft]) marks end-to-end encrypted conversations.
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
    secondary = Color(0xFF28343B),
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
 * Atmospheric canvas background from the approved Radiant Light & OLED Dark design:
 * - Radiant Light: `#FFFFFF -> #F4F7FC -> #EBF1F8` vertical daylight gradient with subtle
 *   warm Signal-orange top-left and cool Lock-blue bottom-right ambient radial glows.
 * - OLED Dark: pure `#000000` canvas with ultra-subtle top-left warm and bottom-right cool glows.
 */
internal fun Modifier.openChatCanvas(): Modifier = composed {
    val isLight = OpenChatPalette.isLight
    this.drawWithCache {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val topGlowRadius = maxOf(w * 0.90f, h * 0.38f).coerceAtLeast(2f)
        val bottomGlowRadius = maxOf(w * 0.86f, h * 0.42f).coerceAtLeast(2f)
        if (isLight) {
            val baseBrush = Brush.verticalGradient(
                0.00f to Color(0xFFFFFFFF),
                0.52f to Color(0xFFF4F7FC),
                1.00f to Color(0xFFEBF1F8),
            )
            val topGlow = Brush.radialGradient(
                colors = listOf(Color(0xFFFF6A30).copy(alpha = 0.105f), Color.Transparent),
                center = Offset(w * 0.18f, h * 0.08f),
                radius = topGlowRadius,
            )
            val bottomGlow = Brush.radialGradient(
                colors = listOf(Color(0xFF247AFF).copy(alpha = 0.105f), Color.Transparent),
                center = Offset(w * 0.88f, h * 0.92f),
                radius = bottomGlowRadius,
            )
            onDrawBehind {
                drawRect(brush = baseBrush)
                drawRect(brush = topGlow)
                drawRect(brush = bottomGlow)
            }
        } else {
            val topGlow = Brush.radialGradient(
                colors = listOf(Color(0xFFFF7840).copy(alpha = 0.085f), Color.Transparent),
                center = Offset(w * 0.20f, h * 0.06f),
                radius = topGlowRadius,
            )
            val bottomGlow = Brush.radialGradient(
                colors = listOf(Color(0xFF5298F2).copy(alpha = 0.075f), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.94f),
                radius = bottomGlowRadius,
            )
            onDrawBehind {
                drawRect(color = Color.Black)
                drawRect(brush = topGlow)
                drawRect(brush = bottomGlow)
            }
        }
    }
}

/** 1dp low-contrast separator matching `--line` in the UI prototype. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    val isLight = OpenChatPalette.isLight
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.75.dp,
        color = if (isLight) Color(0xFF0F1722).copy(alpha = 0.07f) else Color.White.copy(alpha = 0.07f),
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
