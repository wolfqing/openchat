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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
    val primaryAction: Color,
    val onPrimaryAction: Color,
    val vaultBubbleTop: Color,
    val vaultBubbleBottom: Color,
    val onVaultBubble: Color,
    val vaultIncoming: Color,
    val vaultBorder: Color,
    val vaultBannerBg: Color,
    val vaultBannerInk: Color,
)

internal val DarkOpenChatColors = OpenChatColors(
    isLight = false,
    screen = Color(0xFF000000),
    surface = Color(0xFF111319),
    card = Color(0xFF191C24),
    cardRaised = Color(0xFF232732),
    ink = Color(0xFFF2F4F8),
    muted = Color(0xFF969FAF),
    line = Color(0x14FFFFFF),
    outline = Color(0xFF393E4A),
    signal = Color(0xFFFF6B38),
    signalSoft = Color(0x26FF6B38),
    onSignal = Color(0xFFFFFFFF),
    lock = Color(0xFFD8DFEC),
    lockSoft = Color(0x1FD8DFEC),
    online = Color(0xFFFF6B38),
    primaryAction = Color(0xFFEDEBE6),
    onPrimaryAction = Color(0xFF141311),
    vaultBubbleTop = Color(0xFF323846),
    vaultBubbleBottom = Color(0xFF242935),
    onVaultBubble = Color(0xFFF4F6FA),
    vaultIncoming = Color(0xFF161A22),
    vaultBorder = Color(0x61CED8EA),
    vaultBannerBg = Color(0x1CCED8EA),
    vaultBannerInk = Color(0xFFD8DFEC),
)

internal val LightOpenChatColors = OpenChatColors(
    isLight = true,
    screen = Color(0xFFF3F1EC),
    surface = Color(0xFFFAF9F6),
    card = Color(0xFFE7E4DD),
    cardRaised = Color(0xFFDDD9D0),
    ink = Color(0xFF171614),
    muted = Color(0xFF5C574F),
    line = Color(0x1418140E),
    outline = Color(0xFFCBC6BC),
    signal = Color(0xFFE05524),
    signalSoft = Color(0x1AE05524),
    onSignal = Color(0xFFFFFFFF),
    lock = Color(0xFF43464D),
    lockSoft = Color(0x1A43464D),
    online = Color(0xFFE05524),
    primaryAction = Color(0xFF201F1C),
    onPrimaryAction = Color(0xFFFAF9F6),
    vaultBubbleTop = Color(0xFF4B4E56),
    vaultBubbleBottom = Color(0xFF3D4047),
    onVaultBubble = Color(0xFFF7F7F8),
    vaultIncoming = Color(0xFFFAF9F6),
    vaultBorder = Color(0x18171614),
    vaultBannerBg = Color(0x1C43464D),
    vaultBannerInk = Color(0xFF363940),
)

internal val LocalOpenChatColors = staticCompositionLocalOf { DarkOpenChatColors }

/**
 * True inside elevated porcelain/obsidian containers (settings cards, bottom sheets, drawers) so
 * borderless controls placed inside them step cleanly to a tonal neutral fill.
 */
internal val LocalOnElevatedSurface = compositionLocalOf { false }

/**
 * V2 Warm Titanium & Obsidian Vault semantic tokens:
 * - Daylight ground: Warm Titanium (`#F3F1EC`) with sculpted porcelain surfaces (`#FAF9F6`) and
 *   deep titanium charcoal primary actions (`#201F1C`).
 * - OLED Dark ground: pure `#000000` blackout canvas with brightened etched-obsidian encrypted
 *   vault bubbles (`#323846 -> #242935`, mid `#2B303C`).
 * - Signal orange ([Signal]) is the single chromatic brand accent for live radio / mesh activity.
 * - End-to-end encryption ([Lock] / Vault tokens) uses restrained cold tungsten mid-dark gray in
 *   Light mode (`#43464D`) and cold obsidian silver in Dark mode (`#D8DFEC`), with zero bright blue.
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

    /** Elevated card/sheet/drawer background (warm porcelain in light mode, obsidian in OLED dark). */
    val CardSurface: Color
        @Composable get() = if (isLight) Surface else Color(0xFF111319)

    /** Borderless tonal fill for interactive pills, status capsules, and circular controls. */
    val Control: Color
        @Composable get() = when {
            LocalOnElevatedSurface.current && isLight -> Color(0xFFEAE7E0)
            LocalOnElevatedSurface.current -> CardRaised
            isLight -> Card
            else -> Card
        }

    /** Inset tonal well inside cards/sheets (fingerprint grid, relay rows, segmented tracks, list containers). */
    val Well: Color
        @Composable get() = if (isLight) Color(0xFFEBE8E1) else Color(0xFF181B23)

    /** Passive non-interactive badges & dividers on canvas (day headers, system notices, public chip). */
    val ChipPassive: Color
        @Composable get() = if (isLight) Color(0xFFE7E4DD) else Color(0xFF161921)

    val Signal: Color @Composable get() = LocalOpenChatColors.current.signal
    val SignalSoft: Color @Composable get() = LocalOpenChatColors.current.signalSoft
    val OnSignal: Color @Composable get() = LocalOpenChatColors.current.onSignal

    val Lock: Color @Composable get() = LocalOpenChatColors.current.lock
    val LockSoft: Color @Composable get() = LocalOpenChatColors.current.lockSoft

    val Online: Color @Composable get() = LocalOpenChatColors.current.online

    val PrimaryAction: Color @Composable get() = LocalOpenChatColors.current.primaryAction
    val OnPrimaryAction: Color @Composable get() = LocalOpenChatColors.current.onPrimaryAction
    val VaultBubbleTop: Color @Composable get() = LocalOpenChatColors.current.vaultBubbleTop
    val VaultBubbleBottom: Color @Composable get() = LocalOpenChatColors.current.vaultBubbleBottom
    val OnVaultBubble: Color @Composable get() = LocalOpenChatColors.current.onVaultBubble
    val VaultIncoming: Color @Composable get() = LocalOpenChatColors.current.vaultIncoming
    val VaultBorder: Color @Composable get() = LocalOpenChatColors.current.vaultBorder
    val VaultBannerBg: Color @Composable get() = LocalOpenChatColors.current.vaultBannerBg
    val VaultBannerInk: Color @Composable get() = LocalOpenChatColors.current.vaultBannerInk
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
    primary = DarkOpenChatColors.primaryAction,
    onPrimary = DarkOpenChatColors.onPrimaryAction,
    primaryContainer = DarkOpenChatColors.lockSoft,
    onPrimaryContainer = DarkOpenChatColors.ink,
    secondary = Color(0xFF282B33),
    onSecondary = DarkOpenChatColors.ink,
    secondaryContainer = DarkOpenChatColors.cardRaised,
    onSecondaryContainer = DarkOpenChatColors.ink,
    tertiary = DarkOpenChatColors.lock,
    onTertiary = DarkOpenChatColors.onPrimaryAction,
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
    error = Color(0xFFF99A93),
    onError = Color(0xFF2B0600),
    errorContainer = Color(0xFF331512),
    onErrorContainer = Color(0xFFFFDAD2),
)

private val OpenChatLightScheme = lightColorScheme(
    primary = LightOpenChatColors.primaryAction,
    onPrimary = LightOpenChatColors.onPrimaryAction,
    primaryContainer = LightOpenChatColors.lockSoft,
    onPrimaryContainer = LightOpenChatColors.ink,
    secondary = LightOpenChatColors.lock,
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
    error = Color(0xFFA62B22),
    onError = Color.White,
    errorContainer = Color(0x14A62B22),
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
 * Pure architectural canvas ground (zero neon radial corner glows):
 * - Radiant Light: calm gallery-grade alabaster paper (`#F6F6F7`) so pure-white bubbles and
 *   Liquid Glass controls stand out through clean luminance contrast.
 * - OLED Dark: pure `#000000` obsidian canvas for blackout battery saving.
 */
internal fun Modifier.openChatCanvas(): Modifier = composed {
    val bg = OpenChatPalette.Screen
    this.drawWithCache {
        onDrawBehind {
            drawRect(color = bg)
        }
    }
}

/**
 * Cryptographic private vault canvas:
 * - Warm Titanium Light: calm `#F2F0EB` warm gallery surface.
 * - OLED Dark: pure `#000000` blackout ground crowned with a restrained cold-silver obsidian
 *   radial mist at the top (`#1B202A -> #090B0F -> #000000`), signaling an encrypted sanctuary.
 */
internal fun Modifier.openChatVaultCanvas(): Modifier = composed {
    val isLight = OpenChatPalette.isLight
    val bg = if (isLight) Color(0xFFF2F0EB) else Color(0xFF000000)
    this.drawWithCache {
        val mistBrush = if (isLight) {
            null
        } else {
            val radius = maxOf(size.width * 0.95f, 260.dp.toPx())
            Brush.radialGradient(
                0.00f to Color(0xFF1B202A),
                0.48f to Color(0xFF090B0F),
                1.00f to Color(0xFF000000),
                center = Offset(size.width * 0.5f, 0f),
                radius = radius,
            )
        }
        onDrawBehind {
            drawRect(color = bg)
            if (mistBrush != null) {
                drawRect(brush = mistBrush)
            }
        }
    }
}

/**
 * Signature outgoing end-to-end encrypted bubble finish:
 * - Light: B-tier Cold Tungsten Mid-Dark Gray (`#4B4E56 -> #3D4047`, mid `#43464D`).
 * - OLED Dark: Brightened Obsidian Etched Vault (`#323846 -> #242935`, mid `#2B303C`) with
 *   subtle 45-degree etched cryptographic diagonal hatch lines so private chats stand clearly
 *   apart from ordinary OLED public broadcasts without introducing loud colors.
 */
internal fun Modifier.obsidianEtchedBubble(shape: Shape): Modifier = composed {
    val isLight = OpenChatPalette.isLight
    val topColor = OpenChatPalette.VaultBubbleTop
    val bottomColor = OpenChatPalette.VaultBubbleBottom
    this
        .clip(shape)
        .drawWithCache {
            val gradient = Brush.linearGradient(
                colors = listOf(topColor, bottomColor),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.65f, size.height),
            )
            val hatchColor = Color(0xFFE2EAF8).copy(alpha = 0.06f)
            val hatchStep = 7.dp.toPx()
            val hatchWidth = 1.6.dp.toPx()
            onDrawBehind {
                drawRect(brush = gradient)
                if (!isLight && size.width > 1f && size.height > 1f) {
                    val span = size.width + size.height
                    var x = -size.height
                    while (x < span) {
                        drawLine(
                            color = hatchColor,
                            start = Offset(x, size.height),
                            end = Offset(x + size.height, 0f),
                            strokeWidth = hatchWidth,
                        )
                        x += hatchStep
                    }
                }
            }
        }
}

/** 0.5dp low-contrast warm/obsidian separator. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.5.dp,
        color = OpenChatPalette.Line,
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
