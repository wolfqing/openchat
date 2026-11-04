package org.openchat.android.ui.globe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import org.openchat.android.R
import org.openchat.android.nostr.Geohash
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Full-screen globe for choosing a location channel. The cell under the crosshair is the
 * selection; zoom sets its precision. [start] is where the fly-in ends (a geohash), or null
 * for a world view. [onSelect] gets the chosen geohash.
 */
@Composable
fun GlobePickerDialog(start: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        GlobePicker(start, onSelect, onDismiss)
    }
}

@Composable
private fun GlobePicker(start: String?, onSelect: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data by produceState<GlobeData?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { GlobeData.parse(ctx.assets.open("globe.bin").use { it.readBytes() }) }.getOrNull() }
    }
    var view by remember { mutableStateOf(GlobeView(20.0, 10.0, 0.9)) }
    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf<Job?>(null) }

    fun flyTo(target: GlobeView, ms: Int) {
        motion?.cancel()
        val from = view
        motion = scope.launch {
            val t = Animatable(0f)
            t.animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) { view = GlobeView.lerp(from, target, value.toDouble()) }
        }
    }

    fun frame(hash: String) {
        val (lat, lon) = Geohash.center(hash) ?: return
        flyTo(GlobeView(lat.coerceIn(-GlobeView.MAX_LAT, GlobeView.MAX_LAT), lon, GlobeView.zoomFor(hash.length)), 450)
    }

    // Fly in to the starting channel, else stay on the world view.
    LaunchedEffect(start) {
        val hash = start?.takeIf { Geohash.isValid(it) && it.length >= GlobeView.MIN_PRECISION }?.take(GlobeView.MAX_PRECISION) ?: return@LaunchedEffect
        val (lat, lon) = Geohash.center(hash) ?: return@LaunchedEffect
        val scale = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val target = GlobeView(lat, lon, GlobeView.zoomFor(hash.length))
        if (scale == 0f) view = target else flyTo(target, 1600)
    }

    val precision = view.precision
    val cell = Geohash.encode(view.lat, view.lon, precision)
    val colors = GlobeColors(
        ocean = Color(0xFF0F1414), coast = MaterialTheme.colorScheme.onSurfaceVariant, border = MaterialTheme.colorScheme.outline,
        grid = MaterialTheme.colorScheme.outlineVariant, accent = MaterialTheme.colorScheme.primary, label = MaterialTheme.colorScheme.onSurface,
    )
    val measurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "crosshair").animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    val stars = remember { List(140) { Offset(Random.nextFloat(), Random.nextFloat()) to (0.2f + Random.nextFloat() * 0.6f) } }
    val levelName = stringResource(levelLabel(precision))
    val readout = "$levelName · #$cell · ${distanceText(GlobeView.cellWidthMeters(cell))}"

    Box(Modifier.fillMaxSize().background(Color(0xFF060808))) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = readout }
                .pointerInput(Unit) {
                    val baseR = min(size.width, size.height) * 0.42f
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        motion?.cancel()
                        val tracker = VelocityTracker()
                        var multi = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (event.changes.size > 1) multi = true
                            if (zoom != 1f) view = view.zoomedBy(zoom.toDouble())
                            if (pan != Offset.Zero) {
                                view = view.dragged(pan.x / baseR.toDouble(), pan.y / baseR.toDouble())
                                event.changes.forEach { it.consume() }
                            }
                            event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, event.calculateCentroid()) }
                        } while (event.changes.any { it.pressed })
                        if (multi) return@awaitEachGesture
                        // Let a flick keep spinning, slowing to a stop.
                        val v = tracker.calculateVelocity()
                        if (abs(v.x) + abs(v.y) < 300f) return@awaitEachGesture
                        motion = scope.launch {
                            var vx = v.x.toDouble(); var vy = v.y.toDouble(); var last = 0L
                            while (isActive && abs(vx) + abs(vy) > 30) {
                                withFrameNanos { now ->
                                    if (last != 0L) {
                                        val dt = (now - last) / 1e9
                                        view = view.dragged(vx * dt / baseR, vy * dt / baseR)
                                        val k = exp(-dt * 3.5); vx *= k; vy *= k
                                    }
                                    last = now
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    val baseR = min(size.width, size.height) * 0.42f
                    detectTapGestures(
                        // Tap focuses the spot; double tap also goes one level finer.
                        onTap = { o ->
                            val r = baseR * view.zoom
                            view.unproject((o.x - size.width / 2f) / r, -(o.y - size.height / 2f) / r)?.let { (lat, lon) ->
                                flyTo(view.copy(lat = lat.coerceIn(-GlobeView.MAX_LAT, GlobeView.MAX_LAT), lon = lon), 450)
                            }
                        },
                        onDoubleTap = { frame(Geohash.encode(view.lat, view.lon, (view.precision + 1).coerceAtMost(GlobeView.MAX_PRECISION))) },
                    )
                },
        ) {
            drawGlobe(view, data, colors, measurer, stars, pulse)
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            Column {
                Text(stringResource(R.string.globe_title), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.globe_hint), color = Color(0xFF9AA4A3), fontSize = 12.sp)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE6101515)).navigationBarsPadding().padding(16.dp),
        ) {
            Text("#$cell", color = colors.accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("$levelName · ${distanceText(GlobeView.cellWidthMeters(cell))}", color = Color(0xFF9AA4A3), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = precision > GlobeView.MIN_PRECISION, onClick = { frame(cell.take(precision - 1)) }) {
                    Icon(Icons.Filled.Remove, stringResource(R.string.globe_zoom_out), tint = Color.White)
                }
                IconButton(enabled = precision < GlobeView.MAX_PRECISION, onClick = { frame(Geohash.encode(view.lat, view.lon, precision + 1)) }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.globe_zoom_in), tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSelect(cell) }) { Text(stringResource(R.string.globe_select)) }
            }
        }
    }
}

private class GlobeColors(val ocean: Color, val coast: Color, val border: Color, val grid: Color, val accent: Color, val label: Color)

private fun DrawScope.drawGlobe(
    view: GlobeView, data: GlobeData?, c: GlobeColors, measurer: TextMeasurer,
    stars: List<Pair<Offset, Float>>, pulse: Float,
) {
    val w = size.width; val h = size.height
    val cx = w / 2f; val cy = h / 2f
    val r = (min(w, h) * 0.42f * view.zoom).toFloat()
    val p = DoubleArray(3)
    // Off-screen limit: points this far out end a line, so huge zooms never build giant paths.
    val limit = maxOf(w, h) * 20f

    if (r < maxOf(w, h)) {
        stars.forEach { (o, a) -> drawCircle(Color.White.copy(alpha = a * 0.5f), 1.2f, Offset(o.x * w, o.y * h)) }
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.22f), Color.Transparent), Offset(cx, cy), r * 1.12f), r * 1.12f, Offset(cx, cy))
    }
    drawCircle(c.ocean, r, Offset(cx, cy))

    fun line(path: Path, lat: (Int) -> Double, lon: (Int) -> Double, n: Int) {
        var down = false
        for (i in 0 until n) {
            view.project(lat(i), lon(i), p)
            if (p[2] < 0) { down = false; continue }
            val x = cx + (p[0] * r).toFloat(); val y = cy - (p[1] * r).toFloat()
            if (abs(x - cx) > limit || abs(y - cy) > limit) { down = false; continue }
            if (down) path.lineTo(x, y) else { path.moveTo(x, y); down = true }
        }
    }
    fun polylines(list: List<GlobeData.Polyline>, color: Color, width: Float) {
        val path = Path()
        list.forEach { pl -> line(path, { pl.lat[it].toDouble() }, { pl.lon[it].toDouble() }, pl.lat.size) }
        drawPath(path, color, style = Stroke(width))
    }

    // Graticule every 15°, only while the globe is still a globe.
    if (view.zoom < 12) {
        val g = Path()
        for (lon in -180 until 180 step 15) line(g, { -90.0 + it * 3 }, { lon.toDouble() }, 61)
        for (lat in -75..75 step 15) line(g, { lat.toDouble() }, { -180.0 + it * 3 }, 121)
        drawPath(g, c.grid, style = Stroke(1f))
    }
    data?.let { d ->
        polylines(if (view.zoom < 4) d.coastCoarse else d.coastFine, c.coast, 1.4f)
        if (view.zoom > 1.4) polylines(d.borders, c.border, 0.8f)
    }

    // The selected cell and its neighbours, edges drawn as curves on the sphere.
    val precision = view.precision
    val cell = Geohash.encode(view.lat, view.lon, precision)
    val cells = listOf(cell) + Geohash.neighbors(cell)
    cells.forEachIndexed { i, hash ->
        val b = Geohash.bounds(hash) ?: return@forEachIndexed
        val path = Path()
        val steps = 24
        val edge = { k: Int ->
            val s = k % steps / steps.toDouble()
            when (k / steps) {
                0 -> b.latLo to b.lonLo + (b.lonHi - b.lonLo) * s
                1 -> b.latLo + (b.latHi - b.latLo) * s to b.lonHi
                2 -> b.latHi to b.lonHi - (b.lonHi - b.lonLo) * s
                else -> b.latHi - (b.latHi - b.latLo) * s to b.lonLo
            }
        }
        line(path, { edge(it).first }, { edge(it).second }, steps * 4 + 1)
        if (i == 0) {
            drawPath(path, c.accent.copy(alpha = 0.16f))
            drawPath(path, c.accent, style = Stroke(2.5f))
        } else {
            drawPath(path, c.accent.copy(alpha = 0.45f), style = Stroke(1.2f))
        }
        view.project((b.latLo + b.latHi) / 2, (b.lonLo + b.lonHi) / 2, p)
        if (p[2] > 0.15) {
            val style = TextStyle(
                color = if (i == 0) c.accent else c.label.copy(alpha = 0.6f), fontSize = if (i == 0) 13.sp else 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
            )
            val text = measurer.measure("#$hash", style)
            val x = cx + (p[0] * r).toFloat() - text.size.width / 2f
            val y = cy - (p[1] * r).toFloat() - text.size.height / 2f + if (i == 0) 22.dp.toPx() else 0f
            if (x > -text.size.width && x < w && y > -text.size.height && y < h) drawText(text, topLeft = Offset(x, y))
        }
    }

    // Cities: the most prominent first, more as you zoom in, at most 40 labels.
    data?.let { d ->
        val maxRank = when { view.zoom < 1.5 -> 1; view.zoom < 3 -> 3; view.zoom < 8 -> 6; else -> 10 }
        var labels = 0
        val style = TextStyle(color = c.label.copy(alpha = 0.75f), fontSize = 10.sp)
        for (city in d.cities) {
            if (city.rank > maxRank) break
            view.project(city.lat.toDouble(), city.lon.toDouble(), p)
            if (p[2] < 0.05) continue
            val x = cx + (p[0] * r).toFloat(); val y = cy - (p[1] * r).toFloat()
            if (x < 0 || x > w || y < 0 || y > h) continue
            drawCircle(c.label.copy(alpha = 0.8f), 2f, Offset(x, y))
            if (labels++ < 40) drawText(measurer.measure(city.name, style), topLeft = Offset(x + 4f, y - 6f))
        }
    }

    // Pulsing crosshair at the centre: the point that picks the cell.
    val arm = 10.dp.toPx(); val gap = 4.dp.toPx()
    val cross = c.accent.copy(alpha = pulse)
    drawCircle(cross, 14.dp.toPx() * (0.8f + 0.2f * pulse), Offset(cx, cy), style = Stroke(1.5f))
    drawLine(cross, Offset(cx - arm - gap, cy), Offset(cx - gap, cy), 2f)
    drawLine(cross, Offset(cx + gap, cy), Offset(cx + arm + gap, cy), 2f)
    drawLine(cross, Offset(cx, cy - arm - gap), Offset(cx, cy - gap), 2f)
    drawLine(cross, Offset(cx, cy + gap), Offset(cx, cy + arm + gap), 2f)
}

private fun levelLabel(precision: Int): Int = when {
    precision >= 7 -> R.string.geo_level_block
    precision == 6 -> R.string.geo_level_neighborhood
    precision == 5 -> R.string.geo_level_city
    precision == 4 -> R.string.geo_level_province
    else -> R.string.geo_level_region
}

private fun distanceText(meters: Double): String =
    if (meters >= 1000) "~${(meters / 1000).roundToInt()} km" else "~${(meters / 10).roundToInt() * 10} m"
