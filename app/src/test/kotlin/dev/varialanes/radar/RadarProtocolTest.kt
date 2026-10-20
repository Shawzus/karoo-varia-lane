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
