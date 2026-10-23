package dev.varialanes.radar

import kotlin.math.abs

/** A vehicle as shown on screen: the latest radar data plus speeds we trust. */
data class TrackedVehicle(
    val target: RadarTarget,
    /** Positive = getting closer to the rider, m/s. */
    val closingSpeedMs: Double,
    /** Sideways speed in the radar's own sign convention (positive = increasing lateral offset), m/s. */
    val lateralSpeedMs: Double = 0.0,
    /**
     * Lane change in progress (held for a couple of seconds so a 1 Hz display can't miss it):
     * -1 = moving towards negative lateral offset, +1 = towards positive, 0 = none.
     * Radar sign convention; the display applies the user's left/right flip.
     */
    val laneChange: Int = 0,
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
 * - Sideways movement: the radar's sideways speed comes in coarse 0.5 m/s steps, so we also fit
 *   the sideways position over the last [lateralWindowMs] and flag a lane change from either.
 *
 * All times are milliseconds from any monotonic clock. Thread-safe.
 */
class TargetTracker(
    private val staleMs: Long = 1_500,
    private val historyMs: Long = 2_000,
    private val lateralWindowMs: Long = 1_500,
    private val laneChangeHoldMs: Long = 2_000,
) {
    private class Sample(val t: Long, val range: Double, val lateral: Double)

    private class Track(
        val target: RadarTarget,
        val lastSeenMs: Long,
        val history: List<Sample>,
        val laneChangeDir: Int,
        val laneChangeUntilMs: Long,
    )

    private val tracks = LinkedHashMap<Int, Track>()

    @Synchronized
    fun onTargets(targets: List<RadarTarget>, nowMs: Long) {
        if (targets.isEmpty()) {
            tracks.clear()
            return
        }
        for (t in targets) {
            val previous = tracks[t.id]
            val history = ((previous?.history ?: emptyList()) + Sample(nowMs, t.rangeM, t.lateralM))
                .filter { it.t >= nowMs - historyMs }
                .takeLast(MAX_HISTORY)
            var dir = previous?.laneChangeDir ?: 0
            var until = previous?.laneChangeUntilMs ?: 0L
            val detected = laneChangeDirection(t, history, nowMs)
            if (detected != 0) {
                dir = detected
                until = nowMs + laneChangeHoldMs
            }
            tracks[t.id] = Track(t, nowMs, history, dir, until)
        }
    }

    @Synchronized
    fun clear() = tracks.clear()

    /** Current vehicles, nearest first. */
    @Synchronized
    fun snapshot(nowMs: Long): List<TrackedVehicle> {
        tracks.entries.removeAll { nowMs - it.value.lastSeenMs > staleMs }
        return tracks.values
            .map {
                TrackedVehicle(
                    target = it.target,
                    closingSpeedMs = closingSpeed(it),
                    lateralSpeedMs = lateralSpeed(it.target, it.history, nowMs),
                    laneChange = if (nowMs <= it.laneChangeUntilMs) it.laneChangeDir else 0,
                )
            }
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

    private fun derivedClosingSpeed(history: List<Sample>): Double? {
        if (history.size < 2) return null
        val first = history.first()
        val last = history.last()
        val dtS = (last.t - first.t) / 1000.0
        if (dtS < MIN_SPAN_S) return null
        return (first.range - last.range) / dtS
    }

    /** Least-squares slope of sideways position over the recent window, m/s, or null if too little data. */
    private fun derivedLateral(history: List<Sample>, nowMs: Long): Pair<Double, Double>? {
        val window = history.filter { it.t >= nowMs - lateralWindowMs }
        if (window.size < 3) return null
        val spanS = (window.last().t - window.first().t) / 1000.0
        if (spanS < MIN_SPAN_S) return null
        val t0 = window.first().t
        val xs = window.map { (it.t - t0) / 1000.0 }
        val ys = window.map { it.lateral }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0.0) return null
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val displacement = ys.last() - ys.first()
        return slope to displacement
    }

    private fun lateralSpeed(t: RadarTarget, history: List<Sample>, nowMs: Long): Double {
        val derived = derivedLateral(history, nowMs)?.first
        return when {
            abs(t.speedXMs) >= 0.5 -> t.speedXMs
            derived != null -> derived
            else -> 0.0
        }
    }

    private fun laneChangeDirection(t: RadarTarget, history: List<Sample>, nowMs: Long): Int {
        // Radar says it's moving sideways at a lane-change speed.
        if (abs(t.speedXMs) >= REPORTED_LATERAL_MS) return if (t.speedXMs > 0) 1 else -1
        // Or its position has steadily moved sideways.
        val (slope, displacement) = derivedLateral(history, nowMs) ?: return 0
        if (abs(slope) >= DERIVED_LATERAL_MS && abs(displacement) >= MIN_LATERAL_SHIFT_M &&
            (slope > 0) == (displacement > 0)
        ) {
            return if (slope > 0) 1 else -1
        }
        return 0
    }

    companion object {
        private const val MAX_HISTORY = 30
        private const val MIN_SPAN_S = 0.4

        /** Radar-reported sideways speed that counts as a lane change (radar steps are 0.5 m/s). */
        const val REPORTED_LATERAL_MS = 0.5
        /** Fitted sideways speed that counts as a lane change. Educated guess; tune from ride logs. */
        const val DERIVED_LATERAL_MS = 0.4
        /** Minimum sideways shift over the window, so radar jitter (about 0.1–0.3 m) isn't flagged. */
        const val MIN_LATERAL_SHIFT_M = 0.5
    }
}
