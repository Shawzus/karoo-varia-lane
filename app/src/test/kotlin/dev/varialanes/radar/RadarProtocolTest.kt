package dev.varialanes.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Expected values computed with radarble's parseTargetInfo (Python).
    @Test
    fun decodesTargetsLikeRadarble() {
        val p = RadarProtocol.parse(hex("0000" + "05170a00230812fe14" + "09240ff7fc283ffa00"))
        assertTrue(p is RadarPacket.Targets)
        val t = (p as RadarPacket.Targets).targets
        assertEquals(2, t.size)

        assertEquals(5, t[0].id)
        assertEquals(23, t[0].targetClass)
        assertEquals(3.5, t[0].lateralM, 1e-9)
        assertEquals(32.0, t[0].rangeM, 1e-9)
        assertEquals(2.0, t[0].lengthM, 1e-9)
        assertEquals(4.5, t[0].widthM, 1e-9)
        assertEquals(-1.0, t[0].speedXMs, 1e-9)
        assertEquals(10.0, t[0].speedYMs, 1e-9)

        assertEquals(9, t[1].id)
        assertEquals(36, t[1].targetClass)
        assertEquals(-0.4, t[1].lateralM, 1e-9)
        assertEquals(51.0, t[1].rangeM, 1e-9)
        assertEquals(10.0, t[1].lengthM, 1e-9)
        assertEquals(15.75, t[1].widthM, 1e-9)
        assertEquals(-3.0, t[1].speedXMs, 1e-9)
        assertEquals(0.0, t[1].speedYMs, 1e-9)
    }

    @Test
    fun emptyTargetListAndIgnoredPackets() {
        assertEquals(RadarPacket.Targets(emptyList()), RadarProtocol.parse(hex("0000")))
        assertEquals(RadarPacket.Ignored, RadarProtocol.parse(hex("0100aabbcc")))
        assertEquals(RadarPacket.Ignored, RadarProtocol.parse(hex("00")))
    }

    @Test
    fun statusPacket() {
        val p = RadarProtocol.parse(hex("0400050428")) as RadarPacket.Status
        assertEquals(4, p.operationMode)
        assertEquals(10.0, p.deviceSpeedMph!!, 1e-9)
    }

    private fun at(id: Int, lateral: Double, range: Double, sx: Double = 0.0) =
        RadarTarget(id, 23, lateral, range, 4.5, 1.75, sx, 0.0)

    @Test
    fun laneChangeFromSlowDriftIsDetectedAndHeld() {
        val tr = TargetTracker()
        // Drifting left at 0.6 m/s with the radar reporting no sideways speed, 5 samples per second.
        for (i in 0..8) tr.onTargets(listOf(at(1, 3.5 - 0.6 * i * 0.2, 60.0 - i)), i * 200L)
        assertEquals(-1, tr.snapshot(1600).single().laneChange)
        // Stops drifting: warning held for 2 s after the last detection, then cleared.
        for (i in 9..25) tr.onTargets(listOf(at(1, 2.5, 60.0 - i)), i * 200L)
        assertEquals(0, tr.snapshot(25 * 200L).single().laneChange)
    }

    @Test
    fun vehicleSizeIsSteadied() {
        val tr = TargetTracker()
        val lengths = listOf(4.5, 4.75, 8.0, 4.5, 4.25, 4.5, 2.0)
        lengths.forEachIndexed { i, len ->
            tr.onTargets(listOf(RadarTarget(1, 23, 1.0, 50.0 - i, len, 1.75, 0.0, 0.0)), i * 200L)
        }
        // One-off readings of 8 m and 2 m don't change the shown length.
        assertEquals(4.5, tr.snapshot(1200).single().target.lengthM, 1e-9)
    }

    @Test
    fun jitterIsNotALaneChange() {
        val tr = TargetTracker()
        val wobble = listOf(0.0, 0.2, -0.1, 0.25, -0.2, 0.1, -0.15, 0.2, 0.0)
        wobble.forEachIndexed { i, dx -> tr.onTargets(listOf(at(1, 3.5 + dx, 60.0 - i)), i * 200L) }
        assertEquals(0, tr.snapshot(1600).single().laneChange)
    }

    @Test
    fun laneChangeFromReportedSidewaysSpeed() {
        val tr = TargetTracker()
        tr.onTargets(listOf(at(1, 3.5, 40.0, sx = 1.0)), 0)
        assertEquals(1, tr.snapshot(0).single().laneChange)
    }

    @Test
    fun trackerDerivesSpeedAndDropsStale() {
        val tr = TargetTracker()
        fun tgt(id: Int, r: Double, sy: Double = 0.0) = RadarTarget(id, 23, 1.0, r, 4.5, 1.75, 0.0, sy)
        tr.onTargets(listOf(tgt(1, 100.0)), 0)
        tr.onTargets(listOf(tgt(1, 90.0)), 1000)
        assertEquals(10.0, tr.snapshot(1000).single().closingSpeedMs, 1e-9)
        tr.onTargets(listOf(tgt(2, 30.0)), 1000)
        tr.onTargets(listOf(tgt(2, 40.0, sy = 5.0)), 2000)
        assertEquals(-5.0, tr.snapshot(2000).first { it.target.id == 2 }.closingSpeedMs, 1e-9)
        assertTrue(tr.snapshot(3600).isEmpty())
    }
}
