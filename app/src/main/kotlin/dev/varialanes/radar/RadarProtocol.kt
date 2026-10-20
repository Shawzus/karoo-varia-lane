package dev.varialanes.radar

/**
 * Decoder for the Garmin Varia RearVue 820 "radar v2" Bluetooth stream.
 *
 * Ported from rale/radarble (radar_ble/radar.py, handleRadarV2 + parseTargetInfo).
 * Pure Kotlin with no Android dependencies so it can be unit tested on a PC.
 */
data class RadarTarget(
    /** Radar's tracking ID for this vehicle. */
    val id: Int,
    /** Undocumented class code (radarble names 36=HIGH, 23=NORMAL, 26=NORMAL_STABLE, 16=LOW, 13=LOW_STABLE, 4=UNKNOWN). */
    val targetClass: Int,
    /** Sideways offset from the rider in metres. radarble: "X is lane". Sign convention unconfirmed. */
    val lateralM: Double,
    /** Distance behind the rider in metres. */
    val rangeM: Double,
    /** Vehicle length in metres (0.25 m steps). */
    val lengthM: Double,
    /** Vehicle width in metres (0.25 m steps). */
    val widthM: Double,
    /** Sideways speed, m/s (0.5 m/s steps). */
    val speedXMs: Double,
    /** Speed along the road, m/s (0.5 m/s steps). Sign convention unconfirmed. */
    val speedYMs: Double,
)

sealed class RadarPacket {
    /** Packet the decoder skips (radarble ignores packets with header bit 0 set). */
    object Ignored : RadarPacket()

    /** Current list of tracked vehicles. An empty list means no vehicles. */
    data class Targets(val targets: List<RadarTarget>) : RadarPacket()

    /** Radar status: operation mode (see [RadarMode]) and/or bike speed reported by the radar. */
    data class Status(val operationMode: Int?, val deviceSpeedMph: Double?) : RadarPacket()
}

/** radarble's RadarStatus values. */
object RadarMode {
    const val NORMAL = 0
    const val LOW_SPEED = 1
    const val LIMP_MODE = 2
    const val UPDATING = 3
    const val RADAR_BLOCKED = 4
}

object RadarProtocol {
    const val RADAR_SERVICE_UUID = "6a4e3200-667b-11e3-949a-0800200c9a66"
    const val RADAR_V2_MEASUREMENT_UUID = "6a4e3204-667b-11e3-949a-0800200c9a66"
    const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    private const val HEADER_SIZE = 2
    const val TARGET_SIZE = 9

    fun parse(data: ByteArray): RadarPacket {
        if (data.size < HEADER_SIZE) return RadarPacket.Ignored

        val header = u8(data, 0) or (u8(data, 1) shl 8)
        if (header and 1 == 1) return RadarPacket.Ignored

        if (header and 4 != 4) {
            // Target list: header followed by 0..n 9-byte target structs.
            // radarble loops on a fractional count; a trailing partial struct is dropped here instead.
            val count = (data.size - HEADER_SIZE) / TARGET_SIZE
            val targets = (0 until count).map { parseTarget(data, HEADER_SIZE + it * TARGET_SIZE) }
            return RadarPacket.Targets(targets)
        }

        // Device status packet
        if (data.size < 3) return RadarPacket.Ignored
        val flags = u8(data, 2)
        val mode = if (flags and 1 == 1 && data.size > 3) u8(data, 3) else null
        val speedMph = if (flags and 4 == 4) u8(data, data.size - 1) * 0.25 else null
        return RadarPacket.Status(mode, speedMph)
    }

    fun parseTarget(d: ByteArray, o: Int): RadarTarget {
        val id = u8(d, o)
        val targetClass = u8(d, o + 1)

        // 3 bytes, big-endian: top 13 bits = range Y, low 11 bits = range X, both signed.
        val rangeData = (u8(d, o + 2) shl 16) or (u8(d, o + 3) shl 8) or u8(d, o + 4)
        val top = rangeData ushr 11
        var rangeYBits = top and 0x1fff
        var rangeXBits = rangeData and 0x07ff
        if (rangeData and 1024 != 0) rangeXBits -= 2048
        if (top and 4096 != 0) rangeYBits -= 8192

        return RadarTarget(
            id = id,
            targetClass = targetClass,
            lateralM = rangeXBits * 0.1,
            rangeM = rangeYBits * 0.1,
            lengthM = u8(d, o + 5) * 0.25,
            widthM = u8(d, o + 6) * 0.25,
            speedXMs = d[o + 7].toInt() * 0.5, // signed byte
            speedYMs = d[o + 8].toInt() * 0.5, // signed byte
        )
    }

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xff
}
