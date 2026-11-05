package dev.varialanes.radar

/**
 * Decoder for the Garmin Varia RearVue 820 radar Bluetooth stream (characteristic 6a4e3204-…).
 *
 * The characteristic UUIDs come from rale/radarble. The field layout below was worked out from a
 * recorded 80-minute ride (Nov 2026) and differs from radarble's: the three position bytes are
 * little-endian, and the last byte is not a speed.
 *
 * Packet: 2-byte header, then 0..n 9-byte vehicle records.
 *   header[0] bit0 = ignore, bit1 = last packet of this update ("frame"), bit2 = status packet
 *   header[1]      = update sequence number (all packets of one update share it)
 * One update (about 8 per second) can be split over several packets of up to 2 vehicles each.
 * An update with no vehicles is a single 2-byte packet with bit1 set.
 *
 * Vehicle record (9 bytes):
 *   0  id
 *   1  class (radarble names 36/23/26/16/13/4 HIGH/NORMAL/NORMAL_STABLE/LOW/LOW_STABLE/UNKNOWN)
 *   2-4 little-endian 24 bits: low 11 = sideways offset (signed, units ~0.025 m, + = rider's right),
 *       high 13 = distance behind (signed, 0.1 m; negative once a vehicle has drawn level)
 *   5  length (0.25 m), 6 width (0.25 m); 0/0 = not yet known
 *   7  speed along the road (signed, 0.5 m/s; negative = approaching)
 *   8  unknown (always 0x80 in the recording)
 *
 * The sideways scale is an estimate: it's the scale at which overtaking cars read 2–3 m to the
 * side when level with the rider, and following cars read near 0. Distance and speed were
 * cross-checked against each other (distance change rate matches the speed byte).
 *
 * Pure Kotlin with no Android dependencies so it can be unit tested on a PC.
 */
data class RadarTarget(
    val id: Int,
    val targetClass: Int,
    /** Sideways offset from the rider in metres, + = to the rider's right. 0 raw = not measured. */
    val lateralM: Double,
    /** Distance behind the rider in metres. */
    val rangeM: Double,
    /** Vehicle length in metres (0 = unknown). */
    val lengthM: Double,
    /** Vehicle width in metres (0 = unknown). */
    val widthM: Double,
    /** Unused: the 820 doesn't send a sideways speed. Kept for compatibility. */
    val speedXMs: Double,
    /** Speed along the road, m/s; negative = approaching the rider. */
    val speedYMs: Double,
    /** False when the radar sent no sideways measurement (raw value 0). */
    val lateralValid: Boolean = true,
)

sealed class RadarPacket {
    /** Packet the decoder skips. */
    object Ignored : RadarPacket()

    /**
     * Part (or all) of one radar update. [endOfFrame] = this is the last packet of the update, so the
     * vehicles gathered for [sequence] are the complete current set.
     */
    data class Targets(
        val targets: List<RadarTarget>,
        val endOfFrame: Boolean = true,
        val sequence: Int = -1,
    ) : RadarPacket()

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

    /** Metres per unit of the sideways field. Estimated from ride data; see class notes. */
    const val LATERAL_M_PER_UNIT = 0.025
    const val RANGE_M_PER_UNIT = 0.1
    const val SPEED_MS_PER_UNIT = 0.5

    fun parse(data: ByteArray): RadarPacket {
        if (data.size < HEADER_SIZE) return RadarPacket.Ignored
        val flags = u8(data, 0)
        if (flags and 1 == 1) return RadarPacket.Ignored

        if (flags and 4 != 4) {
            val count = (data.size - HEADER_SIZE) / TARGET_SIZE
            val targets = (0 until count).map { parseTarget(data, HEADER_SIZE + it * TARGET_SIZE) }
            return RadarPacket.Targets(targets, endOfFrame = flags and 2 == 2, sequence = u8(data, 1))
        }

        // Device status packet
        if (data.size < 3) return RadarPacket.Ignored
        val sFlags = u8(data, 2)
        val mode = if (sFlags and 1 == 1 && data.size > 3) u8(data, 3) else null
        val speedMph = if (sFlags and 4 == 4) u8(data, data.size - 1) * 0.25 else null
        return RadarPacket.Status(mode, speedMph)
    }

    fun parseTarget(d: ByteArray, o: Int): RadarTarget {
        val packed = u8(d, o + 2) or (u8(d, o + 3) shl 8) or (u8(d, o + 4) shl 16)
        val latRaw = signed(packed and 0x7ff, 11)
        val rangeRaw = signed(packed ushr 11, 13)
        return RadarTarget(
            id = u8(d, o),
            targetClass = u8(d, o + 1),
            lateralM = latRaw * LATERAL_M_PER_UNIT,
            rangeM = rangeRaw * RANGE_M_PER_UNIT,
            lengthM = u8(d, o + 5) * 0.25,
            widthM = u8(d, o + 6) * 0.25,
            speedXMs = 0.0,
            speedYMs = d[o + 7].toInt() * SPEED_MS_PER_UNIT,
            lateralValid = (packed and 0x7ff) != 0,
        )
    }

    private fun signed(v: Int, bits: Int): Int = if (v and (1 shl (bits - 1)) != 0) v - (1 shl bits) else v

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xff
}
