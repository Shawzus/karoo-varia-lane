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
        Tile("POWER 5s", values[DataType.Type.SMOOTHED_5S_AVERAGE_POWER]?.let { it.roundToInt().toString() } ?: DASH, "W"),
        Tile("CADENCE", values[DataType.Type.CADENCE]?.let { it.roundToInt().toString() } ?: DASH, "rpm"),
        Tile("HEART RATE", values[DataType.Type.HEART_RATE]?.let { it.roundToInt().toString() } ?: DASH, "bpm"),
    )

    companion object {
        private const val DASH = "--"
        private val TYPES = listOf(
            DataType.Type.SMOOTHED_5S_AVERAGE_POWER,
            DataType.Type.CADENCE,
            DataType.Type.HEART_RATE,
        )
    }
}
