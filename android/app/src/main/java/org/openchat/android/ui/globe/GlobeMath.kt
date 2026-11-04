package org.openchat.android.ui.globe

import org.openchat.android.nostr.Geohash
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orthographic view of the globe: the point under the crosshair is ([lat], [lon]) and the
 * globe's radius is [zoom] times the radius that fits the screen. Pure maths, so it is tested
 * on the JVM; the picker draws with it.
 */
data class GlobeView(val lat: Double, val lon: Double, val zoom: Double) {
    private val sinLat0 = sin(lat.rad)
    private val cosLat0 = cos(lat.rad)

    /** Unit-sphere coordinates of a point: x right, y up, z towards the viewer (z < 0 is hidden). */
    fun project(lat: Double, lon: Double, out: DoubleArray) {
        val la = lat.rad
        val dl = (lon - this.lon).rad
        val cl = cos(la)
        out[0] = cl * sin(dl)
        out[1] = cosLat0 * sin(la) - sinLat0 * cl * cos(dl)
        out[2] = sinLat0 * sin(la) + cosLat0 * cl * cos(dl)
    }

    /** The (lat, lon) shown at unit-disc point (x, y), or null off the globe. */
    fun unproject(x: Double, y: Double): Pair<Double, Double>? {
        val rho2 = x * x + y * y
        if (rho2 > 1.0) return null
        val z = sqrt(1.0 - rho2)
        val la = asin((z * sinLat0 + y * cosLat0).coerceIn(-1.0, 1.0))
        val dl = atan2(x, z * cosLat0 - y * sinLat0)
        return la.deg to normalizeLon(lon + dl.deg)
    }

    /** Geohash precision for this zoom: about three cells across the visible span. */
    val precision: Int get() = precisionFor(zoom)

    /** Turns the globe by a drag of (dx, dy) in unit-disc lengths (screen pixels / radius). */
    fun dragged(dx: Double, dy: Double): GlobeView = copy(
        lat = (lat + (dy / zoom).deg).coerceIn(-MAX_LAT, MAX_LAT),
        lon = normalizeLon(lon - (dx / zoom).deg / max(cos(lat.rad), 0.2)),
    )

    fun zoomedBy(factor: Double) = copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM))

    companion object {
        const val MIN_ZOOM = 0.8
        const val MAX_ZOOM = 200_000.0
        const val MAX_LAT = 85.0
        const val MIN_PRECISION = 2
        const val MAX_PRECISION = 7 // the block level, the finest channel (§9)

        /** Degrees across the view at [zoom]: the whole hemisphere at 1, shrinking as it grows. */
        fun spanDegrees(zoom: Double) = 180.0 / zoom

        fun precisionFor(zoom: Double): Int {
            val span = spanDegrees(zoom)
            var p = MIN_PRECISION
            // The coarsest cell that still fits three times across the view.
            while (p < MAX_PRECISION && Geohash.cellSize(p).first * 3 > span) p++
            return p
        }

        /** A zoom that frames a [precision] cell with its neighbours: about four cells across. */
        fun zoomFor(precision: Int): Double =
            (180.0 / (Geohash.cellSize(precision).first * 4)).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** Interpolates two views for a fly-to; zoom moves in log space so it feels even. */
        fun lerp(a: GlobeView, b: GlobeView, t: Double): GlobeView {
            var dLon = b.lon - a.lon
            if (dLon > 180) dLon -= 360
            if (dLon < -180) dLon += 360
            return GlobeView(
                a.lat + (b.lat - a.lat) * t,
                normalizeLon(a.lon + dLon * t),
                kotlin.math.exp(ln(a.zoom) + (ln(b.zoom) - ln(a.zoom)) * t),
            )
        }

        fun normalizeLon(lon: Double): Double {
            var l = (lon + 180.0) % 360.0
            if (l < 0) l += 360.0
            return l - 180.0
        }

        /** Rough size of a cell for the readout, in metres: the width at the cell's own latitude. */
        fun cellWidthMeters(hash: String): Double {
            val b = Geohash.bounds(hash) ?: return 0.0
            return (b.lonHi - b.lonLo) * cos(((b.latLo + b.latHi) / 2).rad) * 111_320.0
        }
    }
}

private val Double.rad get() = this * PI / 180.0
private val Double.deg get() = this * 180.0 / PI
