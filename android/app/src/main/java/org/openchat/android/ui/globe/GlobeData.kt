package org.openchat.android.ui.globe

import java.nio.ByteBuffer

/**
 * Map layers for the globe picker, read from assets/globe.bin (see globe.bin.SOURCE).
 *
 * Format, big-endian: "OCG1", then three line layers (coastline 1:110m, coastline 1:50m,
 * land borders 1:110m), each u32 line count and per line u16 point count plus i16 lon, i16 lat
 * per point (degrees × 32767/180); then u16 city count and per city i16 lon, i16 lat,
 * u8 scale rank (0 = most prominent), u8 name length, UTF-8 name. Cities come sorted by rank.
 */
class GlobeData(
    val coastCoarse: List<Polyline>,
    val coastFine: List<Polyline>,
    val borders: List<Polyline>,
    val cities: List<City>,
) {
    /**
     * A piece of a line: points as parallel arrays of degrees, and the same points on the unit
     * sphere ([x] through 0°N 0°E, [y] through 0°N 90°E, [z] through the north pole), worked out
     * once so drawing a frame needs no trigonometry per point. The cap ([capX], [capY], [capZ]
     * its axis, [capAngle] the angle in radians to its farthest point) holds every point, so a
     * piece that cannot be on screen is skipped whole.
     */
    class Polyline(val lat: FloatArray, val lon: FloatArray) {
        val x = FloatArray(lat.size)
        val y = FloatArray(lat.size)
        val z = FloatArray(lat.size)
        val capX: Float
        val capY: Float
        val capZ: Float
        val capAngle: Float

        init {
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            for (i in lat.indices) {
                val la = Math.toRadians(lat[i].toDouble()); val lo = Math.toRadians(lon[i].toDouble())
                val c = Math.cos(la)
                x[i] = (c * Math.cos(lo)).toFloat(); y[i] = (c * Math.sin(lo)).toFloat(); z[i] = Math.sin(la).toFloat()
                sx += x[i]; sy += y[i]; sz += z[i]
            }
            val n = Math.sqrt(sx * sx + sy * sy + sz * sz)
            // The mean direction, or the first point if the piece somehow averages out to nothing.
            if (n > 1e-9) { capX = (sx / n).toFloat(); capY = (sy / n).toFloat(); capZ = (sz / n).toFloat() }
            else if (lat.isNotEmpty()) { capX = x[0]; capY = y[0]; capZ = z[0] }
            else { capX = 1f; capY = 0f; capZ = 0f }
            var far = 1.0
            for (i in lat.indices) far = minOf(far, (x[i] * capX + y[i] * capY + z[i] * capZ).toDouble())
            capAngle = Math.acos(far.coerceIn(-1.0, 1.0)).toFloat()
        }
    }
    class City(val lat: Float, val lon: Float, val rank: Int, val name: String)

    companion object {
        private const val SCALE = 180f / 32767f

        fun parse(bytes: ByteArray): GlobeData? = try {
            val b = ByteBuffer.wrap(bytes)
            val magic = ByteArray(4).also { b.get(it) }
            if (String(magic, Charsets.US_ASCII) != "OCG1") null
            else {
                val layers = List(3) { readLayer(b) }
                val cities = List(b.short.toInt() and 0xFFFF) {
                    val lon = b.short * SCALE
                    val lat = b.short * SCALE
                    val rank = b.get().toInt() and 0xFF
                    val name = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
                    City(lat, lon, rank, String(name, Charsets.UTF_8))
                }
                GlobeData(layers[0], layers[1], layers[2], cities)
            }
        } catch (e: RuntimeException) {
            null
        }

        /** Longest piece a line is cut into. The longest coastline has over 10,000 points; whole, it could never be skipped. */
        const val PIECE = 128

        private fun readLayer(b: ByteBuffer): List<Polyline> {
            val out = ArrayList<Polyline>()
            repeat(b.int) {
                val n = b.short.toInt() and 0xFFFF
                val lat = FloatArray(n)
                val lon = FloatArray(n)
                for (i in 0 until n) { lon[i] = b.short * SCALE; lat[i] = b.short * SCALE }
                // Neighbouring pieces share their end point, so the line stays unbroken.
                var from = 0
                while (from < n - 1 || (from == 0 && n > 0)) {
                    val to = minOf(n, from + PIECE)
                    out += Polyline(lat.copyOfRange(from, to), lon.copyOfRange(from, to))
                    if (to >= n) break
                    from = to - 1
                }
            }
            return out
        }
    }
}
