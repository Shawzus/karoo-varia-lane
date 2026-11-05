package dev.varialanes.radar

import kotlin.math.max

/** A vehicle as shown on screen. */
data class TrackedVehicle(
    /** Latest radar data, with sideways position smoothed/corrected and size steadied. */
    val target: RadarTarget,
    /** Positive = getting closer to the rider, m/s. */
    val closingSpeedMs: Double,
    /** Unused (the 820 sends no sideways speed); kept for compatibility. */
    val lateralSpeedMs: Double = 0.0,
    /** Recent lane change, held for a couple of seconds: -1 = moved in (left), +1 = moved out (right). */
    val laneChange: Int = 0,
    /** 0 = behind the rider, 1 = the next lane to the right, -1 = elsewhere / not yet known. */
    val lane: Int = 0,
)

/**
 * Keeps the current set of vehicles, from complete radar updates ("frames").
 *
 * Tuned against a recorded 80-minute ride:
 * - Each frame is the radar's complete list, so vehicles not in it are removed straight away.
 * - Sideways position is noisy (often ±0.5 m between updates), so the median of the last
 *   [smoothMs] is used, minus a distance-proportional bias ([biasPerMetre]): in the recording,
 *   overtaking cars read ~1.5 cm further right per metre of distance.
 * - Lane: behind the rider below [boundaryM] to the right, next lane above it. A vehicle only
 *   changes lane after crossing [hysteresisM] past the boundary and staying there for [holdMs].
 * - A lane change between [minChangeRangeM] and [maxChangeRangeM] behind is reported as moving
 *   in (-1) or out (+1) for [laneChangeShowMs].
 *
 * All times are milliseconds from any monotonic clock. Thread-safe.
 */
class TargetTracker(
    private val smoothMs: Long = 800,
    private val biasPerMetre: Double = 0.015,
    private val boundaryM: Double = 1.5,
    private val hysteresisM: Double = 0.35,
    private val holdMs: Long = 400,
    private val minChangeRangeM: Double = 6.0,
    private val maxChangeRangeM: Double = 80.0,
    private val laneChangeShowMs: Long = 2_000,
    private val staleMs: Long = 1_500,
) {
    private class Track(
        var target: RadarTarget,
        var lastSeenMs: Long,
        val lateral: ArrayDeque<Pair<Long, Double>> = ArrayDeque(),
        val lengths: ArrayDeque<Double> = ArrayDeque(),
        val widths: ArrayDeque<Double> = ArrayDeque(),
        var lane: Int = -1,
        var candidateLane: Int = -1,
        var candidateSinceMs: Long = 0,
        var changeDir: Int = 0,
        var changeUntilMs: Long = 0,
    )

    private val tracks = LinkedHashMap<Int, Track>()

    /** A complete radar update: replaces the vehicle set. */
    @Synchronized
    fun onFrame(targets: List<RadarTarget>, nowMs: Long) {
        val ids = targets.map { it.id }.toSet()
        tracks.keys.retainAll(ids)
        for (t in targets) {
            val tr = tracks.getOrPut(t.id) { Track(t, nowMs) }
            tr.target = t
            tr.lastSeenMs = nowMs
            if (t.lateralValid) {
                tr.lateral.addLast(nowMs to t.lateralM - biasPerMetre * max(t.rangeM, 0.0))
                while (tr.lateral.isNotEmpty() && tr.lateral.first().first < nowMs - smoothMs) tr.lateral.removeFirst()
            }
            if (t.lengthM > 0) { tr.lengths.addLast(t.lengthM); if (tr.lengths.size > SIZE_SAMPLES) tr.lengths.removeFirst() }
            if (t.widthM > 0) { tr.widths.addLast(t.widthM); if (tr.widths.size > SIZE_SAMPLES) tr.widths.removeFirst() }
            updateLane(tr, nowMs)
        }
    }

    /** Kept for older callers and tests: treats the list as a complete update. */
    fun onTargets(targets: List<RadarTarget>, nowMs: Long) = onFrame(targets, nowMs)

    @Synchronized
    fun clear() = tracks.clear()

    /** Current vehicles, nearest first. */
    @Synchronized
    fun snapshot(nowMs: Long): List<TrackedVehicle> {
        tracks.entries.removeAll { nowMs - it.value.lastSeenMs > staleMs }
        return tracks.values.map { tr ->
            val x = smoothedLateral(tr)
            TrackedVehicle(
                target = tr.target.copy(
                    lateralM = x ?: tr.target.lateralM,
                    lengthM = median(tr.lengths.toList()) ?: tr.target.lengthM,
                    widthM = median(tr.widths.toList()) ?: tr.target.widthM,
                ),
                closingSpeedMs = -tr.target.speedYMs,
                laneChange = if (nowMs <= tr.changeUntilMs) tr.changeDir else 0,
                lane = tr.lane,
            )
        }.sortedBy { it.target.rangeM }
    }

    private fun smoothedLateral(tr: Track): Double? = median(tr.lateral.map { it.second })

    private fun updateLane(tr: Track, nowMs: Long) {
        val x = smoothedLateral(tr) ?: return
        // Well outside the two lanes: not shown.
        if (x > boundaryM + OUTER_LANE_M || x < -LEFT_LIMIT_M) {
            tr.lane = -1
            tr.candidateLane = -1
            return
        }
        if (tr.lane == -1) {
            tr.lane = if (x > boundaryM) 1 else 0
            return
        }
        val want = when (tr.lane) {
            0 -> if (x > boundaryM + hysteresisM) 1 else 0
            else -> if (x < boundaryM - hysteresisM) 0 else 1
        }
        if (want == tr.lane) {
            tr.candidateLane = -1
            return
        }
        if (tr.candidateLane != want) {
            tr.candidateLane = want
            tr.candidateSinceMs = nowMs
            return
        }
        if (nowMs - tr.candidateSinceMs >= holdMs) {
            tr.lane = want
            tr.candidateLane = -1
            if (tr.target.rangeM in minChangeRangeM..maxChangeRangeM) {
                tr.changeDir = if (want == 0) -1 else 1
                tr.changeUntilMs = nowMs + laneChangeShowMs
            }
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    companion object {
        private const val SIZE_SAMPLES = 15
        /** Width of the next lane beyond the boundary, plus margin; further right isn't shown. */
        private const val OUTER_LANE_M = 4.25
        /** Further left than this (kerb side) isn't shown. */
        private const val LEFT_LIMIT_M = 1.5
    }
}
