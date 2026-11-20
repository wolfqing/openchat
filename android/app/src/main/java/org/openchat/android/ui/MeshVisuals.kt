package org.openchat.android.ui

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.openchat.android.R
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.DeliveryState
import org.openchat.android.core.sha256
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// The mesh made visible (docs/design/ui-direction.md, spec §30): who is in range, how far a
// message came, how far one of yours got, and a real screen for the common case of nobody.

/**
 * An identity emblem: a 5x5 left-right mirrored pattern and a colour, both from a hash of the
 * sender key, so the same person looks the same everywhere and two people rarely match.
 */
object Emblem {
    val PALETTE = listOf(
        Color(0xFFE4846B), Color(0xFF6FD6B5), Color(0xFF8B7CF0), Color(0xFFF2C14E),
        Color(0xFF5CB8F0), Color(0xFFE07AB8), Color(0xFF9BCB5A), Color(0xFFD9A47A),
    )

    private val LIGHT_PALETTE = listOf(
        Color(0xFFD94E28), Color(0xFF0D9488), Color(0xFF6941C6), Color(0xFFB45309),
        Color(0xFF0284C7), Color(0xFFBE185D), Color(0xFF047857), Color(0xFFC2410C),
    )

    /** 25 cells, row by row; column 4 mirrors 0 and column 3 mirrors 1. Never empty. */
    fun cells(key: String): BooleanArray {
        val h = sha256(key.lowercase().toByteArray(Charsets.UTF_8))
        val out = BooleanArray(25)
        for (row in 0 until 5) for (col in 0 until 3) {
            val bit = row * 3 + col
            val on = (h[1 + bit / 8].toInt() shr (bit % 8)) and 1 == 1
            out[row * 5 + col] = on
            out[row * 5 + 4 - col] = on
        }
        if (out.none { it }) out[12] = true
        return out
    }

    fun colorIndex(key: String): Int = (sha256(key.lowercase().toByteArray(Charsets.UTF_8))[0].toInt() and 0xFF) % PALETTE.size

    fun color(key: String) = PALETTE[colorIndex(key)]

    @Composable
    fun uiColor(key: String): Color {
        val idx = remember(key) { colorIndex(key) }
        return if (OpenChatPalette.isLight) LIGHT_PALETTE[idx] else PALETTE[idx]
    }
}

@Composable
fun EmblemView(key: String, size: Dp = 36.dp) {
    val cells = remember(key) { Emblem.cells(key) }
    val color = Emblem.uiColor(key)
    val isLight = OpenChatPalette.isLight
    val bgEnd = OpenChatPalette.CardSurface
    val strokeColor = if (isLight) Color(0xFF171614).copy(alpha = 0.09f) else Color.White.copy(alpha = 0.16f)
    val corner = (size.value * 0.29f).coerceIn(8f, 18f).dp
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .border(1.dp, strokeColor, RoundedCornerShape(corner)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.26f), bgEnd),
                    center = Offset(this.size.width * 0.35f, this.size.height * 0.22f),
                    radius = maxOf(this.size.width, this.size.height) * 0.85f,
                ),
            )
            val pad = this.size.minDimension * 0.185f
            val grid = (this.size.minDimension - pad * 2f).coerceAtLeast(4f)
            val step = grid / 5f
            val gap = step * 0.16f
            val dotSize = (step - gap).coerceAtLeast(1.5f)
            val dotRadius = CornerRadius(dotSize * 0.36f, dotSize * 0.36f)
            for (i in 0 until 25) if (cells[i]) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(pad + (i % 5) * step + gap * 0.5f, pad + (i / 5) * step + gap * 0.5f),
                    size = Size(dotSize, dotSize),
                    cornerRadius = dotRadius,
                )
            }
        }
    }
}

/** Filled dots on the four-step path sent → on its way → delivered → read. */
fun deliveryDots(state: DeliveryState): Int = when (state) {
    DeliveryState.SENDING, DeliveryState.FAILED -> 0
    DeliveryState.SENT, DeliveryState.QUEUED -> 1
    DeliveryState.CARRIED -> 2
    DeliveryState.DELIVERED -> 3
    DeliveryState.READ -> 4
}

/** Days in the device's time zone, for day separators. */
fun localDay(ts: Long, zone: TimeZone = TimeZone.getDefault()): Long = Math.floorDiv(ts + zone.getOffset(ts), 86_400_000L)

/** Max gap for consecutive messages from the same sender to share one visual group. */
internal const val GROUP_GAP_MS = 120_000L

/** A chat timeline with a day marker in front of each day's first message and consecutive speaker grouping. */
sealed class TimelineRow(val key: String) {
    class Day(val timestamp: Long, val first: Boolean) : TimelineRow("day-${localDay(timestamp)}")
    class Message(
        val m: ChatMessage,
        val groupStart: Boolean = true,
        val groupEnd: Boolean = true,
    ) : TimelineRow(m.senderKey + m.id)
}

private fun sameSpeakerBurst(a: ChatMessage, b: ChatMessage, zone: TimeZone): Boolean {
    if (a.system || b.system) return false
    if (a.mine != b.mine || a.senderKey != b.senderKey) return false
    if (localDay(a.timestamp, zone) != localDay(b.timestamp, zone)) return false
    return abs(b.timestamp - a.timestamp) <= GROUP_GAP_MS
}

fun withDayBreaks(list: List<ChatMessage>, zone: TimeZone = TimeZone.getDefault()): List<TimelineRow> {
    val out = ArrayList<TimelineRow>(list.size + 4)
    var day = Long.MIN_VALUE
    for (i in list.indices) {
        val m = list[i]
        val d = localDay(m.timestamp, zone)
        if (d != day) { out += TimelineRow.Day(m.timestamp, first = day == Long.MIN_VALUE); day = d }
        val prev = list.getOrNull(i - 1)
        val next = list.getOrNull(i + 1)
        val groupStart = prev == null || !sameSpeakerBurst(prev, m, zone)
        val groupEnd = next == null || !sameSpeakerBurst(m, next, zone)
        out += TimelineRow.Message(m, groupStart = groupStart, groupEnd = groupEnd)
    }
    return out
}

/** "direct" for one link, otherwise "N hops". */
@Composable
fun hopsText(hops: Int): String = if (hops <= 1) stringResource(R.string.hops_direct) else plural(R.plurals.hops_count, hops)

/** The current time, refreshed every second while shown, for "12 s ago" style labels. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    return now
}

enum class AgoUnit { SECONDS, MINUTES, HOURS, DAYS }

/** How long ago [then] was, in the coarsest whole unit. Never negative: a clock that ran ahead reads as now. */
fun agoParts(then: Long, now: Long): Pair<Long, AgoUnit> {
    val s = maxOf(0L, now - then) / 1000
    return when {
        s < 60 -> s to AgoUnit.SECONDS
        s < 3_600 -> s / 60 to AgoUnit.MINUTES
        s < 86_400 -> s / 3_600 to AgoUnit.HOURS
        else -> s / 86_400 to AgoUnit.DAYS
    }
}

private val agoFormatters = HashMap<Locale, RelativeDateTimeFormatter>()

/** "12 sec. ago" in the app's language ([locale] from [appLocale]), not the system's as DateUtils would give. */
fun agoText(then: Long, now: Long, locale: Locale): String {
    val f = agoFormatters.getOrPut(locale) {
        RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.SHORT, DisplayContext.CAPITALIZATION_NONE)
    }
    val (n, unit) = agoParts(then, now)
    if (n == 0L) return f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
    return f.format(
        n.toDouble(), RelativeDateTimeFormatter.Direction.LAST,
        when (unit) {
            AgoUnit.SECONDS -> RelativeDateTimeFormatter.RelativeUnit.SECONDS
            AgoUnit.MINUTES -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
            AgoUnit.HOURS -> RelativeDateTimeFormatter.RelativeUnit.HOURS
            AgoUnit.DAYS -> RelativeDateTimeFormatter.RelativeUnit.DAYS
        },
    )
}

/** "Today", "Yesterday", otherwise the date, in the app's language. */
fun dayText(timestamp: Long, now: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String {
    val f = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.LONG, DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
    return when (localDay(now, zone) - localDay(timestamp, zone)) {
        0L -> f.format(RelativeDateTimeFormatter.Direction.THIS, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        1L -> f.format(RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).apply { timeZone = zone }.format(java.util.Date(timestamp))
    }
}

/** Whether system animator duration scale is zero ("Remove animations"). */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Signal-orange dot: solid with a soft expanding ring when live, hollow when scanning. */
@Composable
internal fun SignalPulseDot(live: Boolean) {
    val still = rememberReducedMotion()
    val accent = OpenChatPalette.Signal
    if (!live) {
        Box(Modifier.size(16.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).border(1.5.dp, accent, CircleShape))
        }
        return
    }
    if (still) {
        Box(Modifier.size(16.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).background(accent, CircleShape))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "signal-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ring",
    )
    Canvas(Modifier.size(16.dp).clearAndSetSemantics { }) {
        val baseR = 4.2.dp.toPx()
        val ringR = baseR * (0.9f + 1.0f * progress)
        drawCircle(accent.copy(alpha = (1f - progress) * 0.65f), radius = ringR, center = center, style = Stroke(1.3.dp.toPx()))
        drawCircle(accent, radius = baseR, center = center)
    }
}

/** Vector equalizer bars for Live PTT walkie-talkie playback and recording capsules. */
@Composable
internal fun LiveEqualizerBars(
    active: Boolean = true,
    level: Float? = null,
    barCount: Int = 4,
    color: Color = OpenChatPalette.Signal,
    modifier: Modifier = Modifier.size(width = 16.dp, height = 14.dp),
) {
    val still = rememberReducedMotion()
    val phase = if (!active || still) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "ptt-eq")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        p
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val step = size.width / barCount
        val barW = (step * 0.58f).coerceAtLeast(2.dp.toPx())
        for (i in 0 until barCount) {
            val wave = if (!active || still) {
                0.45f + (i % 2) * 0.25f
            } else {
                0.35f + 0.65f * abs(sin(phase + i * 0.92f))
            }
            val amp = if (level != null) {
                (0.22f + level.coerceIn(0.06f, 1f) * 0.78f * wave).coerceIn(0.20f, 1f)
            } else {
                wave
            }
            val h = (size.height * amp).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = Offset(i * step + (step - barW) * 0.5f, (size.height - h) * 0.5f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f),
            )
        }
    }
}

private val STATUS_CAPSULE_MIN_HEIGHT = 32.dp

/**
 * Floating signal & Live Walkie-Talkie status row on the main tabs:
 * shows Direct / Reach / Last-heard in a compact flat capsule (tappable to open the nearby
 * people sheet when peers are in range), morphs into a clickable animated walkie-talkie speaker
 * banner when a peer transmits live PTT, and pairs with the `[ Talk ]` drawer toggle pill on the
 * right of the Nearby tab. Both sides share a locked vertical height so switching tabs never
 * shifts the capsule's Y position.
 */
@Composable
internal fun NearbyStatusCapsuleRow(
    direct: Int,
    reach: Int,
    lastHeard: Long?,
    scanningSince: Long?,
    liveSpeakerLabel: String?,
    pttOpen: Boolean = false,
    onTogglePtt: (() -> Unit)? = null,
    onSpeakerClick: (() -> Unit)? = null,
    onPeersClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    val locale = appLocale()
    val signal = OpenChatPalette.Signal
    val canOpenPeers = liveSpeakerLabel == null && scanningSince == null && reach > 0 && onPeersClick != null
    val openPeersDesc = stringResource(R.string.nearby_peers_open)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 6.dp)
            .defaultMinSize(minHeight = STATUS_CAPSULE_MIN_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FlatCapsule(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = if (onTogglePtt != null) 8.dp else 0.dp)
                .defaultMinSize(minHeight = STATUS_CAPSULE_MIN_HEIGHT)
                .let {
                    when {
                        liveSpeakerLabel != null && onSpeakerClick != null ->
                            it.pressScale(0.97f).clip(CircleShape).clickable(role = Role.Button, onClick = onSpeakerClick)
                        canOpenPeers ->
                            it.pressScale(0.97f)
                                .clip(CircleShape)
                                .semantics { contentDescription = openPeersDesc }
                                .clickable(role = Role.Button, onClick = onPeersClick)
                        else -> it
                    }
                },
            containerColor = if (liveSpeakerLabel != null) OpenChatPalette.SignalSoft else null,
            contentAlignment = Alignment.CenterStart,
        ) {
            AnimatedContent(
                targetState = liveSpeakerLabel,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                label = "nearby-signal-or-ptt",
            ) { speaker ->
                if (speaker != null) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        LiveEqualizerBars(active = true, color = signal)
                        Text(
                            stringResource(R.string.live_ptt_speaking, speaker),
                            style = TabularMonoStyle,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (OpenChatPalette.isLight) Color(0xFFC93B06) else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (scanningSince != null) {
                    val secs = ((now - scanningSince) / 1000).coerceAtLeast(0)
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        SignalPulseDot(live = false)
                        Text(
                            stringResource(R.string.band_scanning),
                            style = TabularMonoStyle,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 11.5.sp, lineHeight = 16.sp)
                        Text(
                            stringResource(R.string.band_for, String.format(locale, "%d:%02d", secs / 60, secs % 60)),
                            style = TabularMonoStyle,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                } else {
                    Row(
                        Modifier.padding(start = 12.dp, end = if (canOpenPeers) 8.dp else 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        SignalPulseDot(live = true)
                        BandNumber(stringResource(R.string.band_direct), direct, highlight = false)
                        Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 11.5.sp, lineHeight = 16.sp)
                        BandNumber(stringResource(R.string.band_reach), reach, highlight = true)
                        lastHeard?.let {
                            Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 11.5.sp, lineHeight = 16.sp)
                            Text(
                                agoText(it, now, locale),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = TabularMonoStyle,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                maxLines = 1,
                            )
                        }
                        if (canOpenPeers) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = OpenChatPalette.Muted,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
        if (onTogglePtt != null) {
            FluidGlassPillButton(
                onClick = onTogglePtt,
                modifier = Modifier.defaultMinSize(minHeight = STATUS_CAPSULE_MIN_HEIGHT),
                tintColor = when {
                    pttOpen -> signal
                    liveSpeakerLabel != null -> OpenChatPalette.SignalSoft
                    else -> null
                },
                borderAccent = if (pttOpen || liveSpeakerLabel != null) signal else null,
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = WalkieTalkieIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (pttOpen) Color.White else signal,
                )
                Text(
                    stringResource(R.string.ptt_button),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pttOpen) Color.White else OpenChatPalette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The signal band under the top bar: neighbours, everyone reachable, and when we last heard anyone. */
@Composable
fun SignalBand(direct: Int, reach: Int, lastHeard: Long?) {
    val now = rememberNow()
    val locale = appLocale()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().background(OpenChatPalette.Surface).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SignalPulseDot(live = true)
        BandNumber(stringResource(R.string.band_direct), direct, highlight = false)
        Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 13.sp)
        BandNumber(stringResource(R.string.band_reach), reach, highlight = true)
        Spacer(Modifier.weight(1f))
        lastHeard?.let {
            Text(agoText(it, now, locale), color = muted, style = TabularMonoStyle, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BandNumber(label: String, n: Int, highlight: Boolean = false) {
    val tone = if (highlight) OpenChatPalette.Signal else MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$n",
            style = TabularMonoStyle,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = tone,
        )
        Text(
            label.lowercase(appLocale()),
            style = TabularMonoStyle,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
            color = tone,
        )
    }
}

/** The band while nobody is in range: we are scanning, and for how long. */
@Composable
fun ScanningBand(since: Long) {
    val now = rememberNow()
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().background(OpenChatPalette.Surface).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SignalPulseDot(live = false)
        Text(
            stringResource(R.string.band_scanning),
            style = TabularMonoStyle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.band_for, String.format(appLocale(), "%d:%02d", secs / 60, secs % 60)),
            style = TabularMonoStyle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Under a private chat's title: how many links away the peer is, our neighbours, and when we last heard them. */
@Composable
fun RouteBand(
    name: String,
    hops: Int?,
    direct: Int,
    lastHeard: Long,
    liveSpeakerLabel: String? = null,
    onSpeakerClick: (() -> Unit)? = null,
) {
    val now = rememberNow()
    val locale = appLocale()
    val resources = LocalResources.current
    val signal = OpenChatPalette.Signal
    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 6.dp)) {
        FlatCapsule(
            modifier = Modifier
                .defaultMinSize(minHeight = STATUS_CAPSULE_MIN_HEIGHT)
                .let {
                    if (liveSpeakerLabel != null && onSpeakerClick != null) {
                        it.pressScale(0.97f).clip(CircleShape).clickable(role = Role.Button, onClick = onSpeakerClick)
                    } else {
                        it
                    }
                },
            containerColor = if (liveSpeakerLabel != null) OpenChatPalette.SignalSoft else null,
            contentAlignment = Alignment.CenterStart,
        ) {
            if (liveSpeakerLabel != null) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    LiveEqualizerBars(active = true, color = signal)
                    Text(
                        stringResource(R.string.live_ptt_speaking, liveSpeakerLabel),
                        style = TabularMonoStyle,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (OpenChatPalette.isLight) Color(0xFFC93B06) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    SignalPulseDot(live = true)
                    Text(
                        if (hops == null) stringResource(R.string.dm_relayed) else resources.getQuantityString(R.plurals.route_hops, hops, hops, name),
                        style = TabularMonoStyle,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 11.5.sp)
                    BandNumber(stringResource(R.string.band_direct), direct, highlight = false)
                    Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 11.5.sp)
                    Text(agoText(lastHeard, now, locale), color = MaterialTheme.colorScheme.onSurfaceVariant, style = TabularMonoStyle, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Flat matte pill naming who can read a conversation (Public vs End-to-End Encrypted). */
@Composable
fun AudiencePill(encrypted: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val fg = if (encrypted) OpenChatPalette.Lock else MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(if (encrypted) R.string.chip_encrypted else R.string.chip_public)
    FlatCapsule(
        modifier = modifier,
        containerColor = if (encrypted) OpenChatPalette.LockSoft else OpenChatPalette.ChipPassive,
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 5.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (encrypted) Icons.Outlined.Lock else Icons.Outlined.Sensors,
                contentDescription = if (compact) label else null,
                tint = fg,
                modifier = Modifier.size(13.dp),
            )
            if (!compact) {
                Text(label, color = fg, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Four dots joined by a line; the filled ones are the steps reached. A failure shows one red dot. */
@Composable
fun DeliveryPathDots(state: DeliveryState) {
    val filled = deliveryDots(state)
    val accent = OpenChatPalette.Signal
    val failed = MaterialTheme.colorScheme.error
    val empty = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(width = 42.dp, height = 9.dp)) {
        val r = size.height / 2 - 0.5f
        val step = (size.width - 2 * r - 2) / 3
        for (i in 0 until 3) {
            val x0 = r + 1 + i * step
            drawLine(if (i + 1 < filled) accent else empty, Offset(x0 + r, size.height / 2), Offset(x0 + step - r, size.height / 2), strokeWidth = 1.4f.dp.toPx())
        }
        for (i in 0 until 4) {
            val c = Offset(r + 1 + i * step, size.height / 2)
            when {
                state == DeliveryState.FAILED && i == 0 -> drawCircle(failed, r, c)
                i < filled -> drawCircle(accent, r, c)
                else -> drawCircle(if (state == DeliveryState.FAILED) failed.copy(alpha = 0.45f) else empty, r - 0.7f.dp.toPx(), c, style = Stroke(1.3f.dp.toPx()))
            }
        }
    }
}

/** A day separator; in a private chat the first one also says who can read it. */
@Composable
fun DayHeader(timestamp: Long, note: String?) {
    val day = dayText(timestamp, System.currentTimeMillis(), appLocale())
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text(
            if (note == null) day else "$day · $note",
            modifier = Modifier
                .background(OpenChatPalette.ChipPassive, CircleShape)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { heading() },
            textAlign = TextAlign.Center,
            style = TabularMonoStyle,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Nobody in range: the most common state, so it gets a real screen. A smooth VSync radar sweep
 * says the phone is looking; three cards say what actually helps.
 */
@Composable
fun NobodyNearby(
    onShareApp: (() -> Unit)?,
    onShowHistory: (() -> Unit)? = null,
    onOpenGuide: (() -> Unit)? = null,
    bottomClearance: Dp = 0.dp,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availHeight = (maxHeight - bottomClearance).coerceAtLeast(160.dp)
        val compact = availHeight < 440.dp
        val radarMax = if (compact) {
            minOf(236.dp, maxWidth * 0.70f)
        } else {
            minOf(296.dp, maxWidth * 0.80f)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = if (compact) 6.dp else 10.dp,
                    bottom = 16.dp + bottomClearance,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        ) {
            Radar(Modifier.widthIn(max = radarMax).fillMaxWidth().aspectRatio(1f))
            Text(
                stringResource(R.string.radar_title),
                fontSize = if (compact) 18.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.radar_body),
                fontSize = if (compact) 13.5.sp else 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (onShowHistory != null || onOpenGuide != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onShowHistory?.let {
                        FluidGlassPillButton(
                            onClick = it,
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.radar_history),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    onOpenGuide?.let {
                        FluidGlassPillButton(
                            onClick = it,
                            contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = null,
                                tint = OpenChatPalette.Signal,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                stringResource(R.string.guide_title),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            TipCard(1, stringResource(R.string.tip_open_title), stringResource(R.string.tip_open_body), null)
            TipCard(2, stringResource(R.string.tip_window_title), stringResource(R.string.tip_window_body), null)
            TipCard(3, stringResource(R.string.tip_share_title), stringResource(R.string.tip_share_body), onShareApp)
        }
    }
}

@Composable
private fun TipCard(n: Int, title: String, body: String, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenChatPalette.CardSurface,
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.pressScale(0.98f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else it },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .size(24.dp)
                    .background(OpenChatPalette.Signal, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", color = Color.White, style = TabularMonoStyle, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(body, fontSize = 12.5.sp, lineHeight = 18.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically).size(20.dp),
                )
            }
        }
    }
}

/**
 * Rings at about 10, 30 and 50+ metres, typical Bluetooth range indoors and out, with a smooth
 * VSync sweep and 360-degree azimuth dial ticks.
 */
@Composable
private fun Radar(modifier: Modifier) {
    val still = rememberReducedMotion()
    var sweep by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        while (true) withFrameNanos { now ->
            sweep = sweepAngle(now / 1_000_000)
        }
    }
    val isLight = OpenChatPalette.isLight
    val ring = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.60f)
    val accent = OpenChatPalette.Signal
    val hubBg = if (isLight) OpenChatPalette.Screen else Color(0xFF0A0A09)
    val dot = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum", fontSize = 11.sp, color = labelColor)
    Box(modifier.clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.minDimension / 2 - 4.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = if (isLight) 0.06f else 0.085f), accent.copy(alpha = if (isLight) 0.015f else 0.02f)),
                    center = c,
                    radius = maxR.coerceAtLeast(2f),
                ),
                radius = maxR,
                center = c,
            )
            val radii = listOf(maxR * 0.36f, maxR * 0.68f, maxR)
            radii.forEach { drawCircle(ring, it, c, style = Stroke(1.15.dp.toPx())) }
            for (deg in 0 until 360 step 15) {
                val rad = deg * (PI / 180.0)
                val major = deg % 45 == 0
                val inner = maxR - (if (major) 6.dp.toPx() else 3.dp.toPx())
                drawLine(
                    color = if (major) tickColor else ring,
                    start = Offset(c.x + inner * cos(rad).toFloat(), c.y + inner * sin(rad).toFloat()),
                    end = Offset(c.x + maxR * cos(rad).toFloat(), c.y + maxR * sin(rad).toFloat()),
                    strokeWidth = if (major) 1.25.dp.toPx() else 0.85.dp.toPx(),
                )
            }
            listOf("10 m", "30 m", "50 m+").forEachIndexed { i, label ->
                val t = measurer.measure(label, labelStyle)
                drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - radii[i] + 5.dp.toPx()))
            }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = sweep }) {
            val c = center
            val maxR = size.minDimension / 2 - 4.dp.toPx()
            if (!still) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.04f), accent.copy(alpha = 0.40f)), c),
                    startAngle = -52f, sweepAngle = 52f, useCenter = true,
                    topLeft = Offset(c.x - maxR, c.y - maxR), size = Size(maxR * 2, maxR * 2),
                )
                drawLine(
                    color = accent.copy(alpha = 0.78f),
                    start = c,
                    end = Offset(c.x + maxR, c.y),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            drawCircle(hubBg, 7.5.dp.toPx(), c)
            drawCircle(accent, 5.2.dp.toPx(), c)
            drawCircle(dot, 2.3.dp.toPx(), c)
        }
    }
}

private const val SWEEP_MS = 4_000L

/** Where the sweep points at [timeMs]: one turn every four seconds. */
internal fun sweepAngle(timeMs: Long): Float = Math.floorMod(timeMs, SWEEP_MS) * 360f / SWEEP_MS
