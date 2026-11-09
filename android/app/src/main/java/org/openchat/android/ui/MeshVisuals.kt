package org.openchat.android.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.openchat.android.R
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.DeliveryState
import org.openchat.android.core.sha256
import java.util.TimeZone

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
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(size / 6),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / 5
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

/** A chat timeline with a day marker in front of each day's first message. */
sealed class TimelineRow(val key: String) {
    class Day(val timestamp: Long, val first: Boolean) : TimelineRow("day-${localDay(timestamp)}")
    class Message(val m: ChatMessage) : TimelineRow(m.senderKey + m.id)
}

fun withDayBreaks(list: List<ChatMessage>, zone: TimeZone = TimeZone.getDefault()): List<TimelineRow> {
    val out = ArrayList<TimelineRow>(list.size + 4)
    var day = Long.MIN_VALUE
    for (m in list) {
        val d = localDay(m.timestamp, zone)
        if (d != day) { out += TimelineRow.Day(m.timestamp, first = day == Long.MIN_VALUE); day = d }
        out += TimelineRow.Message(m)
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

fun agoText(then: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(minOf(then, now), now, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** The signal band under the top bar: neighbours, everyone reachable, and when we last heard anyone. */
@Composable
fun SignalBand(direct: Int, reach: Int, lastHeard: Long?) {
    val now = rememberNow()
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(accent, CircleShape))
        BandNumber(stringResource(R.string.band_direct), direct)
        Text("|", color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        BandNumber(stringResource(R.string.band_reach), reach)
        Spacer(Modifier.weight(1f))
        lastHeard?.let { Text(agoText(it, now), color = muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1) }
    }
}

@Composable
private fun BandNumber(label: String, n: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Text("$n", fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** The band while nobody is in range: we are scanning, and for how long. */
@Composable
fun ScanningBand(since: Long) {
    val now = rememberNow()
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(12.dp).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(stringResource(R.string.band_scanning), fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.band_for, "%d:%02d".format(secs / 60, secs % 60)),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Under a private chat's title: how many links away the peer is, our neighbours, and when we last heard them. */
@Composable
fun RouteBand(name: String, hops: Int?, direct: Int, lastHeard: Long) {
    val now = rememberNow()
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(
            if (hops == null) stringResource(R.string.dm_relayed) else ctx.resources.getQuantityString(R.plurals.route_hops, hops, hops, name),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Text("|", color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        BandNumber(stringResource(R.string.band_direct), direct)
        Text(agoText(lastHeard, now), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
    }
}

/** An outlined pill naming who can read a conversation. */
@Composable
fun AudiencePill(encrypted: Boolean) {
    val color = if (encrypted) Color(0xFF8DB8F2) else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(50), color = Color.Transparent,
        border = BorderStroke(1.dp, if (encrypted) color else MaterialTheme.colorScheme.outline),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (encrypted) Icons.Outlined.Lock else Icons.Outlined.Sensors, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(stringResource(if (encrypted) R.string.chip_encrypted else R.string.chip_public), color = color, fontSize = 13.sp)
        }
    }
}

/** Four dots joined by a line; the filled ones are the steps reached. A failure shows one red dot. */
@Composable
fun DeliveryPathDots(state: DeliveryState) {
    val filled = deliveryDots(state)
    val accent = MaterialTheme.colorScheme.primary
    val failed = MaterialTheme.colorScheme.error
    val empty = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(width = 44.dp, height = 10.dp)) {
        val r = size.height / 2 - 1
        val step = (size.width - 2 * r - 2) / 3
        for (i in 0 until 3) {
            val x0 = r + 1 + i * step
            drawLine(if (i + 1 < filled) accent else empty, Offset(x0 + r, size.height / 2), Offset(x0 + step - r, size.height / 2), strokeWidth = 1.5f.dp.toPx())
        }
        for (i in 0 until 4) {
            val c = Offset(r + 1 + i * step, size.height / 2)
            when {
                state == DeliveryState.FAILED && i == 0 -> drawCircle(failed, r, c)
                i < filled -> drawCircle(accent, r, c)
                else -> drawCircle(if (state == DeliveryState.FAILED) failed.copy(alpha = 0.5f) else empty, r - 0.75f.dp.toPx(), c, style = Stroke(1.5f.dp.toPx()))
            }
        }
    }
}

/** A day separator; in a private chat the first one also says who can read it. */
@Composable
fun DayHeader(timestamp: Long, note: String?) {
    val now = System.currentTimeMillis()
    val day = DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.DAY_IN_MILLIS).toString()
    Text(
        if (note == null) day else "$day · $note",
        Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 24.dp).semantics { heading() },
        textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Nobody in range: the most common state, so it gets a real screen. A slow radar sweep
 * says the phone is looking; three cards say what actually helps.
 */
@Composable
fun NobodyNearby(onShareApp: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Radar(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f))
        Text(
            stringResource(R.string.radar_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.radar_body), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(4.dp))
        TipCard(1, stringResource(R.string.tip_open_title), stringResource(R.string.tip_open_body), null)
        TipCard(2, stringResource(R.string.tip_window_title), stringResource(R.string.tip_window_body), null)
        TipCard(3, stringResource(R.string.tip_share_title), stringResource(R.string.tip_share_body), onShareApp)
    }
}

@Composable
private fun TipCard(n: Int, title: String, body: String, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(28.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) { Text("$n", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Rings at about 10, 30 and 50+ metres, typical Bluetooth range indoors and out, with a sweep. */
@Composable
private fun Radar(modifier: Modifier) {
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    val ring = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = labelColor)
    Canvas(modifier.clearAndSetSemantics { }) {
        val c = center
        val maxR = size.minDimension / 2 - 2
        val radii = listOf(maxR * 0.36f, maxR * 0.68f, maxR)
        radii.forEach { drawCircle(ring, it, c, style = Stroke(1.dp.toPx())) }
        rotate(sweep, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.05f), accent.copy(alpha = 0.45f)), c),
                startAngle = -40f, sweepAngle = 40f, useCenter = true,
                topLeft = Offset(c.x - maxR, c.y - maxR), size = Size(maxR * 2, maxR * 2),
            )
        }
        drawCircle(dot, 5.dp.toPx(), c)
        // Each label sits just inside the top of its ring.
        listOf("10 m", "30 m", "50 m+").forEachIndexed { i, label ->
            val t = measurer.measure(label, labelStyle)
            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - radii[i] + 3.dp.toPx()))
        }
    }
}
