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
    /** Points as parallel arrays of degrees. */
    class Polyline(val lat: FloatArray, val lon: FloatArray)
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

        private fun readLayer(b: ByteBuffer): List<Polyline> = List(b.int) {
            val n = b.short.toInt() and 0xFFFF
            val lat = FloatArray(n)
            val lon = FloatArray(n)
            for (i in 0 until n) { lon[i] = b.short * SCALE; lat[i] = b.short * SCALE }
            Polyline(lat, lon)
        }
    }
}
