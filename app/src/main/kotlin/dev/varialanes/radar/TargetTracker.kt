package dev.varialanes.radar

import kotlin.math.abs

/** A vehicle as shown on screen: the latest radar data plus a closing speed we trust. */
data class TrackedVehicle(
    val target: RadarTarget,
    /** Positive = getting closer to the rider, m/s. */
    val closingSpeedMs: Double,
)

/**
 * Keeps the current set of vehicles.
 *
 * - Targets are keyed by radar ID, because a long target list may arrive split over several
 *   Bluetooth notifications.
 * - A vehicle not reported for [staleMs] is dropped. An empty target packet clears everything.
 * - Closing speed: the 820 reports a road-direction speed, but its sign convention is unconfirmed
 *   and Cadence reported in Feb 2026 that early 820 firmware didn't send speed at all. So we also
 *   work out speed from how the range changes, and use that for the sign (or the whole value
 *   if the radar sends zero).
 *
 * All times are milliseconds from any monotonic clock. Thread-safe.
 */
class TargetTracker(
    private val staleMs: Long = 1_500,
    private val historyMs: Long = 2_000,
) {
    private class Track(val target: RadarTarget, val lastSeenMs: Long, val history: List<Pair<Long, Double>>)

    private val tracks = LinkedHashMap<Int, Track>()

    @Synchronized
    fun onTargets(targets: List<RadarTarget>, nowMs: Long) {
        if (targets.isEmpty()) {
            tracks.clear()
            return
        }
        for (t in targets) {
            val previous = tracks[t.id]
            val history = ((previous?.history ?: emptyList()) + (nowMs to t.rangeM))
                .filter { it.first >= nowMs - historyMs }
                .takeLast(MAX_HISTORY)
            tracks[t.id] = Track(t, nowMs, history)
        }
    }

    @Synchronized
    fun clear() = tracks.clear()

    /** Current vehicles, nearest first. */
    @Synchronized
    fun snapshot(nowMs: Long): List<TrackedVehicle> {
        tracks.entries.removeAll { nowMs - it.value.lastSeenMs > staleMs }
        return tracks.values
            .map { TrackedVehicle(it.target, closingSpeed(it)) }
            .sortedBy { it.target.rangeM }
    }

    private fun closingSpeed(track: Track): Double {
        val derived = derivedClosingSpeed(track.history)
        val reported = abs(track.target.speedYMs)
        return when {
            reported >= 0.25 -> {
                // Trust the radar's magnitude; take the direction from the range trend if we have one.
                if (derived != null && derived < -0.5) -reported else reported
            }
            else -> derived ?: 0.0
        }
    }

    private fun derivedClosingSpeed(history: List<Pair<Long, Double>>): Double? {
        if (history.size < 2) return null
        val (t0, r0) = history.first()
        val (t1, r1) = history.last()
        val dtS = (t1 - t0) / 1000.0
        if (dtS < MIN_SPAN_S) return null
        return (r0 - r1) / dtS
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val MIN_SPAN_S = 0.4
    }
}
