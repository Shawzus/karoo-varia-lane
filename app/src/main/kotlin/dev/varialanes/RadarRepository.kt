package dev.varialanes

import android.os.SystemClock
import dev.varialanes.radar.RadarPacket
import dev.varialanes.radar.RadarProtocol
import dev.varialanes.radar.TargetTracker
import dev.varialanes.radar.TrackedVehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Shared radar state for the Bluetooth connection, the data fields and the settings screen. */
object RadarRepository {
    enum class Link { NOT_PAIRED, PAIRING, SEARCHING, CONNECTED }

    data class Status(
        val link: Link = Link.NOT_PAIRED,
        val radarMode: Int? = null,
        val packetCount: Long = 0,
        val message: String? = null,
        val batteryPercent: Int? = null,
    )

    data class Frame(
        val vehicles: List<TrackedVehicle>,
        val status: Status,
    )

    private val tracker = TargetTracker()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val recentPackets = ArrayDeque<String>()

    fun setLink(link: Link, message: String? = null) {
        if (link != _status.value.link || message != _status.value.message) RideRecorder.event("status $link ${message ?: ""}")
        if (link != Link.CONNECTED) tracker.clear()
        _status.update { it.copy(link = link, message = message) }
    }

    fun setBattery(percent: Int) = _status.update { it.copy(batteryPercent = percent) }

    /** Feed one raw notification from the radar characteristic. Returns the vehicle count afterwards. */
    fun onRadarNotification(bytes: ByteArray): Int {
        val now = SystemClock.elapsedRealtime()
        logPacket(bytes)
        RideRecorder.packet(bytes)
        when (val packet = RadarProtocol.parse(bytes)) {
            is RadarPacket.Targets -> tracker.onTargets(packet.targets, now)
            is RadarPacket.Status -> packet.operationMode?.let { mode -> _status.update { it.copy(radarMode = mode) } }
            RadarPacket.Ignored -> Unit
        }
        _status.update { it.copy(packetCount = it.packetCount + 1) }
        return tracker.snapshot(now).size
    }

    fun frame(): Frame = Frame(tracker.snapshot(SystemClock.elapsedRealtime()), _status.value)

    @Synchronized
    fun recentPackets(): List<String> = recentPackets.toList()

    private val events = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    /** Bluetooth step-by-step log shown in the app, for troubleshooting. */
    @Synchronized
    fun log(event: String) {
        RideRecorder.event(event)
        events.addFirst("${clock.format(java.util.Date())} $event")
        while (events.size > 25) events.removeLast()
    }

    @Synchronized
    fun events(): List<String> = events.toList()

    @Synchronized
    private fun logPacket(bytes: ByteArray) {
        recentPackets.addFirst(bytes.joinToString("") { "%02x".format(it) })
        while (recentPackets.size > 12) recentPackets.removeLast()
    }
}
