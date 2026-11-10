package dev.varialanes.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Packets below are real ones from a recorded ride (5 Nov 2026). Expected values were computed
 * independently in Python from the layout documented in RadarProtocol.
 */
class RadarProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun decodesRealVehicleRecords() {
        val p = RadarProtocol.parse(hex("02f4541a0128310000ee80")) as RadarPacket.Targets
        assertTrue(p.endOfFrame)
        assertEquals(0xf4, p.sequence)
        val t = p.targets.single()
        assertEquals(84, t.id)
        assertEquals(26, t.targetClass)
        assertEquals(0.1, t.lateralM, 1e-9)
        assertEquals(157.3, t.rangeM, 1e-9)
        assertEquals(-9.0, t.speedYMs, 1e-9) // approaching at 9 m/s
        assertTrue(t.lateralValid)

        val n = (RadarProtocol.parse(hex("0208541afff7300000ee80")) as RadarPacket.Targets).targets.single()
        assertEquals(-0.1, n.lateralM, 1e-9)
        assertEquals(156.6, n.rangeM, 1e-9)

        val sized = (RadarProtocol.parse(hex("02bc541a00b0261007ec80")) as RadarPacket.Targets).targets.single()
        assertEquals(123.8, sized.rangeM, 1e-9)
        assertEquals(4.0, sized.lengthM, 1e-9)
        assertEquals(1.75, sized.widthM, 1e-9)
        assertEquals(-10.0, sized.speedYMs, 1e-9)
        assertFalse(sized.lateralValid) // raw sideways 0 = not measured
    }

    @Test
    fun negativeRangeOnceLevel() {
        val t = (RadarProtocol.parse(hex("0210" + "0a1a1428ff0000f080")) as RadarPacket.Targets).targets.single()
        assertEquals(-2.7, t.rangeM, 1e-9)
        assertEquals(2.0, t.lateralM, 1e-9)
    }

    @Test
    fun splitFramesAndEmptyUpdates() {
        val first = RadarProtocol.parse(hex("008c" + "2f1a0128310000ee80" + "301a0128310000ee80")) as RadarPacket.Targets
        assertFalse(first.endOfFrame)
        assertEquals(2, first.targets.size)
        val last = RadarProtocol.parse(hex("028c" + "351a0128310000ee80")) as RadarPacket.Targets
        assertTrue(last.endOfFrame)
        assertEquals(first.sequence, last.sequence)
        val empty = RadarProtocol.parse(hex("02cc")) as RadarPacket.Targets
        assertTrue(empty.endOfFrame)
        assertTrue(empty.targets.isEmpty())
        assertEquals(RadarPacket.Ignored, RadarProtocol.parse(hex("01ccaa")))
    }

    @Test
    fun statusPacket() {
        val p = RadarProtocol.parse(hex("06680500001c")) as RadarPacket.Status
        assertEquals(0, p.operationMode)
        assertEquals(7.0, p.deviceSpeedMph!!, 1e-9)
    }

    // ---- tracker ----

    private fun v(id: Int, lateral: Double, range: Double, speed: Double = -8.0) =
        RadarTarget(id, 23, lateral, range, 4.0, 1.75, 0.0, speed)

    @Test
    fun frameReplacesVehicleSet() {
        val tr = TargetTracker()
        tr.onFrame(listOf(v(1, 0.0, 40.0), v(2, 3.0, 60.0)), 0)
        assertEquals(2, tr.snapshot(0).size)
        tr.onFrame(listOf(v(2, 3.0, 59.0)), 100)
        assertEquals(listOf(2), tr.snapshot(100).map { it.target.id })
        tr.onFrame(emptyList(), 200)
        assertTrue(tr.snapshot(200).isEmpty())
    }

    @Test
    fun closingSpeedFromRadar() {
        val tr = TargetTracker()
        tr.onFrame(listOf(v(1, 0.0, 40.0, speed = -12.5)), 0)
        assertEquals(12.5, tr.snapshot(0).single().closingSpeedMs, 1e-9)
    }

    @Test
    fun lanesFromSideways() {
        val tr = TargetTracker(biasPerMetre = 0.0)
        tr.onFrame(listOf(v(1, 0.3, 30.0), v(2, 3.2, 30.0), v(3, 9.0, 30.0), v(4, -3.0, 30.0)), 0)
        val lanes = tr.snapshot(0).associate { it.target.id to it.lane }
        assertEquals(0, lanes[1]) // behind you
        assertEquals(1, lanes[2]) // next lane
        assertEquals(-1, lanes[3]) // further right: not shown
        assertEquals(-1, lanes[4]) // well left: not shown
    }

    @Test
    fun noisySidewaysDoesNotFlickerLanes() {
        val tr = TargetTracker(biasPerMetre = 0.0)
        // Following car near the boundary wobbling ±0.4 m: one wild reading must not switch lanes.
        val xs = listOf(1.2, 1.4, 1.0, 1.3, 2.4, 1.1, 1.2, 1.5, 0.9, 1.3)
        xs.forEachIndexed { i, x -> tr.onFrame(listOf(v(1, x, 40.0 - i)), i * 120L) }
        val s = tr.snapshot(xs.size * 120L).single()
        assertEquals(0, s.lane)
        assertEquals(0, s.laneChange)
    }

    @Test
    fun pullingOutAndMovingInAreReported() {
        val tr = TargetTracker(biasPerMetre = 0.0)
        var t = 0L
        fun step(x: Double, r: Double) { tr.onFrame(listOf(v(7, x, r)), t); t += 120 }
        repeat(10) { step(0.2, 60.0 - it) }          // behind you
        repeat(15) { step(3.2, 50.0 - it) }          // pulls out
        val out = tr.snapshot(t)
        assertEquals(1, out.single().lane)
        assertEquals(1, out.single().laneChange)
        repeat(30) { step(3.2, 35.0 - it * 0.2) }    // stays out; warning expires
        assertEquals(0, tr.snapshot(t).single().laneChange)
        repeat(15) { step(0.1, 29.0 - it * 0.2) }    // moves back in behind you
        val inn = tr.snapshot(t).single()
        assertEquals(0, inn.lane)
        assertEquals(-1, inn.laneChange)
    }

    @Test
    fun noLaneChangeReportedWhenAlongside() {
        val tr = TargetTracker(biasPerMetre = 0.0)
        var t = 0L
        repeat(10) { tr.onFrame(listOf(v(9, 3.0, 10.0 - it)), t); t += 120 }
        repeat(15) { tr.onFrame(listOf(v(9, 0.5, 2.0)), t); t += 120 } // 2 m behind: too close to trust
        assertEquals(0, tr.snapshot(t).single().laneChange)
    }

    @Test
    fun closePassRiskForCarsOnCourseToPassClose() {
        fun tv(lateral: Double, range: Double, closingKmh: Double, change: Int = 0) =
            TrackedVehicle(RadarTarget(1, 23, lateral, range, 4.0, 1.75, 0.0, -closingKmh / 3.6), closingKmh / 3.6,
                laneChange = change, lane = if (lateral > 1.5) 1 else 0)
        assertTrue(TargetTracker.isClosePassRisk(tv(0.5, 12.0, 30.0)))   // right behind you
        assertTrue(TargetTracker.isClosePassRisk(tv(2.2, 12.0, 30.0)))   // over a bit, still under ~1.5 m gap
        assertFalse(TargetTracker.isClosePassRisk(tv(3.5, 12.0, 30.0)))  // a full lane over
        assertFalse(TargetTracker.isClosePassRisk(tv(0.5, 12.0, 30.0, 1))) // pulling out
        assertFalse(TargetTracker.isClosePassRisk(tv(0.5, 12.0, 5.0)))   // barely faster than you
        assertFalse(TargetTracker.isClosePassRisk(tv(0.5, 30.0, 30.0)))  // still far back
    }

    @Test
    fun vehicleSizeIsSteadied() {
        val tr = TargetTracker()
        listOf(4.5, 4.75, 8.0, 4.5, 4.25, 4.5, 2.0).forEachIndexed { i, len ->
            tr.onFrame(listOf(RadarTarget(1, 23, 0.0, 50.0 - i, len, 1.75, 0.0, -5.0)), i * 120L)
        }
        assertEquals(4.5, tr.snapshot(840).single().target.lengthM, 1e-9)
    }

    @Test
    fun threeVehicleSizes() {
        assertEquals(VehicleSize.SMALL, VehicleSize.classify(2.0, 1.0))
        assertEquals(VehicleSize.CAR, VehicleSize.classify(4.0, 1.75))
        assertEquals(VehicleSize.LARGE, VehicleSize.classify(15.0, 2.25))
        assertEquals(VehicleSize.CAR, VehicleSize.classify(0.0, 0.0))
    }
}
