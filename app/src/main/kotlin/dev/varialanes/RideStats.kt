package dev.varialanes

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Live ride numbers from the Karoo for the stat tiles beside the full-screen radar.
 * Karoo units: speed m/s, distance m, times ms, power W, heart rate bpm, cadence rpm.
 */
class RideStats(context: Context) {
    data class Tile(val label: String, val value: String, val unit: String)

    private val karooSystem = KarooSystemService(context.applicationContext)
    private val values = ConcurrentHashMap<String, Double>()
    private val consumerIds = ArrayList<String>()

    fun start() {
        karooSystem.connect { connected ->
            if (!connected) return@connect
            synchronized(consumerIds) {
                if (consumerIds.isNotEmpty()) return@connect
                for (type in TYPES) {
                    consumerIds += karooSystem.addConsumer(OnStreamState.StartStreaming(type)) { event: OnStreamState ->
                        when (val st = event.state) {
                            is StreamState.Streaming -> st.dataPoint.singleValue?.let { values[type] = it } ?: values.remove(type)
                            else -> values.remove(type)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(consumerIds) {
            consumerIds.forEach { runCatching { karooSystem.removeConsumer(it) } }
            consumerIds.clear()
        }
        runCatching { karooSystem.disconnect() }
    }

    fun tiles(): List<Tile> = listOf(
        Tile("SPEED", values[DataType.Type.SPEED]?.let { fmt1(it * 3.6) } ?: DASH, "km/h"),
        Tile("POWER 3s", values[DataType.Type.SMOOTHED_3S_AVERAGE_POWER]?.let { it.roundToInt().toString() } ?: DASH, "W"),
        Tile("HEART RATE", values[DataType.Type.HEART_RATE]?.let { it.roundToInt().toString() } ?: DASH, "bpm"),
        Tile("CADENCE", values[DataType.Type.CADENCE]?.let { it.roundToInt().toString() } ?: DASH, "rpm"),
        Tile("DISTANCE", values[DataType.Type.DISTANCE]?.let { fmt1(it / 1000) } ?: DASH, "km"),
        Tile("RIDE TIME", values[DataType.Type.RIDE_TIME]?.let { hms(it) } ?: DASH, ""),
    )

    companion object {
        private const val DASH = "--"
        private val TYPES = listOf(
            DataType.Type.SPEED,
            DataType.Type.SMOOTHED_3S_AVERAGE_POWER,
            DataType.Type.HEART_RATE,
            DataType.Type.CADENCE,
            DataType.Type.DISTANCE,
            DataType.Type.RIDE_TIME,
        )

        private fun fmt1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

        private fun hms(ms: Double): String {
            val s = (ms / 1000).toLong()
            return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }
}
