package org.openchat.android.ui

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.provider.Settings
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlin.math.abs

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
}

@Composable
fun EmblemView(key: String, size: Dp = 36.dp) {
    val cells = remember(key) { Emblem.cells(key) }
    val color = remember(key) { Emblem.color(key) }
    val corner = (size.value * 0.22f).coerceIn(6f, 16f).dp
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(OpenChatPalette.Surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corner))
            .padding(size / 5.5f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / 5f
            for (i in 0 until 25) if (cells[i]) {
                drawRect(color, Offset((i % 5) * cell, (i / 5) * cell), Size(cell + 0.5f, cell + 0.5f))
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
        Box(Modifier.size(14.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(9.dp).border(1.5.dp, accent, CircleShape))
        }
        return
    }
    if (still) {
        Box(Modifier.size(14.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
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

private val TabularMonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")

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
        BandNumber(stringResource(R.string.band_direct), direct)
        Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 13.sp)
        BandNumber(stringResource(R.string.band_reach), reach)
        Spacer(Modifier.weight(1f))
        lastHeard?.let {
            Text(agoText(it, now, locale), color = muted, style = TabularMonoStyle, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BandNumber(label: String, n: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TabularMonoStyle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Text("$n", style = TabularMonoStyle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OpenChatPalette.Signal)
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
fun RouteBand(name: String, hops: Int?, direct: Int, lastHeard: Long) {
    val now = rememberNow()
    val locale = appLocale()
    val resources = LocalResources.current
    Row(
        Modifier.fillMaxWidth().background(OpenChatPalette.Surface).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SignalPulseDot(live = true)
        Text(
            if (hops == null) stringResource(R.string.dm_relayed) else resources.getQuantityString(R.plurals.route_hops, hops, hops, name),
            style = TabularMonoStyle,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("·", color = MaterialTheme.colorScheme.outline, style = TabularMonoStyle, fontSize = 13.sp)
        BandNumber(stringResource(R.string.band_direct), direct)
        Text(agoText(lastHeard, now, locale), color = MaterialTheme.colorScheme.onSurfaceVariant, style = TabularMonoStyle, fontSize = 12.sp, maxLines = 1)
    }
}

/** An outlined pill naming who can read a conversation. */
@Composable
fun AudiencePill(encrypted: Boolean, modifier: Modifier = Modifier) {
    val fg = if (encrypted) OpenChatPalette.Lock else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (encrypted) OpenChatPalette.Lock.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
    val bg = if (encrypted) OpenChatPalette.LockSoft.copy(alpha = 0.5f) else Color.Transparent
    Surface(
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(if (encrypted) Icons.Outlined.Lock else Icons.Outlined.Sensors, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            Text(stringResource(if (encrypted) R.string.chip_encrypted else R.string.chip_public), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                .background(OpenChatPalette.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { heading() },
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Nobody in range: the most common state, so it gets a real screen. A slow radar sweep
 * says the phone is looking; three cards say what actually helps.
 */
@Composable
fun NobodyNearby(onShareApp: (() -> Unit)?, onShowHistory: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Radar(Modifier.widthIn(max = 236.dp).fillMaxWidth().aspectRatio(1f))
        Text(
            stringResource(R.string.radar_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.radar_body),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        onShowHistory?.let {
            Surface(
                shape = CircleShape,
                color = OpenChatPalette.Surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.clickable(onClick = it),
            ) {
                Text(
                    stringResource(R.string.radar_history),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        TipCard(1, stringResource(R.string.tip_open_title), stringResource(R.string.tip_open_body), null)
        TipCard(2, stringResource(R.string.tip_window_title), stringResource(R.string.tip_window_body), null)
        TipCard(3, stringResource(R.string.tip_share_title), stringResource(R.string.tip_share_body), onShareApp)
    }
}

@Composable
private fun TipCard(n: Int, title: String, body: String, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OpenChatPalette.Surface,
        border = BorderStroke(1.dp, if (onClick != null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.padding(top = 1.dp).size(24.dp).border(1.5.dp, OpenChatPalette.Signal, CircleShape).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", color = OpenChatPalette.Signal, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * Rings at about 10, 30 and 50+ metres, typical Bluetooth range indoors and out, with a sweep.
 */
@Composable
private fun Radar(modifier: Modifier) {
    val still = rememberReducedMotion()
    var sweep by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (now - last >= 1_000_000_000L / SWEEP_FPS) { last = now; sweep = sweepAngle(now / 1_000_000) }
        }
    }
    val ring = MaterialTheme.colorScheme.outlineVariant
    val accent = OpenChatPalette.Signal
    val dot = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = labelColor)
    Box(modifier.clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.minDimension / 2 - 2
            val radii = listOf(maxR * 0.36f, maxR * 0.68f, maxR)
            radii.forEach { drawCircle(ring, it, c, style = Stroke(1.dp.toPx())) }
            listOf("10 m", "30 m", "50 m+").forEachIndexed { i, label ->
                val t = measurer.measure(label, labelStyle)
                drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - radii[i] + 3.dp.toPx()))
            }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = sweep }) {
            val c = center
            val maxR = size.minDimension / 2 - 2
            if (!still) drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.04f), accent.copy(alpha = 0.42f)), c),
                startAngle = -42f, sweepAngle = 42f, useCenter = true,
                topLeft = Offset(c.x - maxR, c.y - maxR), size = Size(maxR * 2, maxR * 2),
            )
            drawCircle(OpenChatPalette.Screen, 7.dp.toPx(), c)
            drawCircle(dot, 4.5.dp.toPx(), c)
        }
    }
}

private const val SWEEP_FPS = 15
private const val SWEEP_MS = 4_000L

/** Where the sweep points at [timeMs]: one turn every four seconds. */
internal fun sweepAngle(timeMs: Long): Float = Math.floorMod(timeMs, SWEEP_MS) * 360f / SWEEP_MS
