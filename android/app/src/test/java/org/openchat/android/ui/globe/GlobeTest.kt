package org.openchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.nostr.Geohash
import java.io.File

class GlobeTest {
    @Test
    fun projectionCentreAndInverse() {
        val v = GlobeView(48.85, 2.35, 1.0)
        val p = DoubleArray(3)
        v.project(48.85, 2.35, p)
        assertEquals(0.0, p[0], 1e-9); assertEquals(0.0, p[1], 1e-9); assertEquals(1.0, p[2], 1e-9)
        v.project(-48.85, -177.65, p) // the antipode is hidden
        assertTrue(p[2] < -0.99)
        for ((lat, lon) in listOf(50.0 to 5.0, 40.0 to -10.0, 60.0 to 30.0)) {
            v.project(lat, lon, p)
            val (la, lo) = v.unproject(p[0], p[1])!!
            assertEquals(lat, la, 1e-6); assertEquals(lon, lo, 1e-6)
        }
        assertNull(v.unproject(0.8, 0.8))
    }

    @Test
    fun inverseAcrossTheAntimeridian() {
        val v = GlobeView(0.0, 179.0, 1.0)
        val p = DoubleArray(3)
        v.project(0.0, -178.0, p)
        assertEquals(-178.0, v.unproject(p[0], p[1])!!.second, 1e-6)
        assertEquals(-179.0, GlobeView.normalizeLon(181.0), 1e-9)
    }

    @Test
    fun aSettledLevelFillsMostOfTheScreenAndStaysOnThatLevel() {
        val screenSpan = 180.0 / Math.PI / 0.42 // degrees across the short side at zoom 1
        for (p in GlobeView.MIN_PRECISION + 1..GlobeView.MAX_PRECISION) for (lat in listOf(0.0, 25.0, 50.0, 70.0, -60.0)) {
            val z = GlobeView.zoomFor(p, lat)
            assertEquals("p=$p lat=$lat", p, GlobeView.precisionFor(z))
            val gridShare = 3 * org.openchat.android.nostr.Geohash.cellSize(p).first * Math.cos(Math.toRadians(lat)) * z / screenSpan
            // Never the speck that piled the labels up (a sixth of the screen), never off the edges.
            assertTrue("p=$p lat=$lat share=$gridShare", gridShare in 0.4..GlobeView.GRID_FILL + 1e-9)
            if (kotlin.math.abs(lat) <= 50) assertEquals(GlobeView.GRID_FILL, gridShare, 1e-9)
        }
    }

    @Test
    fun precisionFollowsZoomAndBackAgain() {
        var last = 0
        var z = GlobeView.MIN_ZOOM
        while (z < GlobeView.MAX_ZOOM) {
            val p = GlobeView.precisionFor(z)
            assertTrue("precision never goes down as you zoom in", p >= last)
            last = p; z *= 1.3
        }
        assertEquals(GlobeView.MIN_PRECISION, GlobeView.precisionFor(1.0))
        assertEquals(GlobeView.MAX_PRECISION, last)
        // Framing a precision lands on that precision, so the -/+ buttons step exactly one level.
        for (p in GlobeView.MIN_PRECISION..GlobeView.MAX_PRECISION) assertEquals(p, GlobeView.precisionFor(GlobeView.zoomFor(p)))
    }

    @Test
    fun dragTurnsTheGlobeAndClampsAtThePoles() {
        val v = GlobeView(0.0, 0.0, 1.0)
        assertTrue(v.dragged(0.1, 0.0).lon < 0) // finger moves right, the map follows it
        assertTrue(v.dragged(0.0, 0.1).lat > 0)
        assertEquals(GlobeView.MAX_LAT, v.dragged(0.0, 10.0).lat, 1e-9)
        // At high zoom the same drag turns it far less.
        assertTrue(abs(GlobeView(0.0, 0.0, 1000.0).dragged(0.1, 0.0).lon) < 0.01)
    }

    @Test
    fun cellBoundsAndSizes() {
        val b = Geohash.bounds("wtw3s")!!
        val (lat, lon) = Geohash.center("wtw3s")!!
        assertTrue(lat in b.latLo..b.latHi && lon in b.lonLo..b.lonHi)
        assertEquals(Geohash.cellSize(5).first, b.lonHi - b.lonLo, 1e-12)
        assertEquals(Geohash.cellSize(5).second, b.latHi - b.latLo, 1e-12)
        assertEquals("wtw3s", Geohash.encode(lat, lon, 5))
        assertEquals(8, Geohash.neighbors("wtw3s").size)
        // A city-level cell near Shanghai is roughly 4–5 km wide.
        assertTrue(GlobeView.cellWidthMeters("wtw3s") in 3_500.0..5_000.0)
        assertNull(Geohash.bounds("wtw3a"))
    }

    @Test
    fun bundledMapDataParses() {
        val d = GlobeData.parse(File("src/main/assets/globe.bin").readBytes())
        assertNotNull(d)
        d!!
        assertTrue(d.coastCoarse.size > 100 && d.coastFine.size > d.coastCoarse.size && d.borders.isNotEmpty())
        assertTrue(d.cities.any { it.name == "Tokyo" && abs(it.lat - 35.7f) < 0.3f })
        assertTrue(d.coastFine.all { pl -> pl.lat.all { it in -90f..90f } && pl.lon.all { it in -180f..180f } })
        assertNull(GlobeData.parse("nope".toByteArray()))
        assertNull(GlobeData.parse(File("src/main/assets/globe.bin").readBytes().copyOf(1000)))
    }

    @Test
    fun storedUnitVectorsProjectLikeLatitudeAndLongitude() {
        val d = GlobeData.parse(File("src/main/assets/globe.bin").readBytes())!!
        val a = DoubleArray(3); val b = DoubleArray(3)
        for (v in listOf(GlobeView(20.0, 10.0, 0.9), GlobeView(-41.3, 174.8, 40.0), GlobeView(64.1, -21.9, 3_000.0))) {
            for (pl in d.coastCoarse.filterIndexed { i, _ -> i % 7 == 0 }) for (i in pl.x.indices) {
                v.project(pl.lat[i].toDouble(), pl.lon[i].toDouble(), a)
                v.projectUnit(pl.x[i], pl.y[i], pl.z[i], b)
                for (k in 0..2) assertEquals(a[k], b[k], 1e-6)
            }
        }
    }

    @Test
    fun linesAreCutIntoPiecesThatStillJoinUp() {
        val d = GlobeData.parse(File("src/main/assets/globe.bin").readBytes())!!
        val all = d.coastCoarse + d.coastFine + d.borders
        assertTrue(all.all { it.lat.size in 1..GlobeData.PIECE })
        // globe.bin holds 5127 + 60412 + 2994 points in 134 + 1429 + 333 lines; a cut repeats one point.
        assertEquals(5127 + 60412 + 2994, all.sumOf { it.lat.size } - (all.size - (134 + 1429 + 333)))
        // The longest coastline (10,297 points) is now many pieces, each starting where the last ended.
        val joins = d.coastFine.zipWithNext().count { (p, q) -> p.lat.last() == q.lat.first() && p.lon.last() == q.lon.first() }
        assertTrue(joins >= (10_297 - 1) / (GlobeData.PIECE - 1))
        // Every point lies inside its piece's cap.
        for (pl in all) for (i in pl.x.indices) {
            val towards = (pl.x[i] * pl.capX + pl.y[i] * pl.capY + pl.z[i] * pl.capZ).toDouble().coerceIn(-1.0, 1.0)
            assertTrue(Math.acos(towards) <= pl.capAngle + 1e-3)
        }
    }

    @Test
    fun aPieceWithAPointOnScreenIsNeverSkipped() {
        val d = GlobeData.parse(File("src/main/assets/globe.bin").readBytes())!!
        val p = DoubleArray(3)
        val reachAtZoom1 = Math.hypot(0.5, 1.1) / 0.42 // a tall phone: half diagonal over the globe's radius
        var kept = 0; var skipped = 0
        for (v in listOf(GlobeView(20.0, 10.0, 0.9), GlobeView(35.7, 139.7, 12.0), GlobeView(51.5, -0.1, 800.0), GlobeView(-33.9, 151.2, 60_000.0), GlobeView(85.0, 0.0, 5.0))) {
            val reach = reachAtZoom1 / v.zoom
            val visible = GlobeView.visibleAngle(reach)
            for (pl in d.coastFine) {
                val inView = v.capInView(pl.capX, pl.capY, pl.capZ, pl.capAngle, visible)
                if (inView) kept++ else skipped++
                if (inView) continue
                for (i in pl.x.indices) {
                    v.projectUnit(pl.x[i], pl.y[i], pl.z[i], p)
                    assertTrue("a skipped piece had a point on screen", p[2] < 0 || Math.hypot(p[0], p[1]) > reach)
                }
            }
        }
        // And it is worth it: zoomed in, nearly everything is skipped.
        assertTrue("kept=$kept skipped=$skipped", skipped > kept)
    }

    @Test
    fun crowdedLabelsGiveWayToOnesAlreadyDrawn() {
        val space = LabelSpace()
        space.reserve(40f, 40f, 60f, 60f) // the crosshair
        assertTrue(space.claim(0f, 0f, 30f, 10f))
        assertTrue(!space.claim(20f, 5f, 50f, 15f)) // covers the first label
        assertTrue(!space.claim(45f, 45f, 80f, 55f)) // covers the crosshair
        assertTrue(space.claim(30f, 0f, 50f, 10f)) // touching edges is fine
        assertTrue(space.claim(0f, 70f, 30f, 80f))
        // A refused claim takes no space.
        assertTrue(space.isFree(61f, 41f, 70f, 50f))
    }

    private fun abs(x: Double) = kotlin.math.abs(x)
    private fun abs(x: Float) = kotlin.math.abs(x)
}
