package dev.varialanes

import android.content.Context

data class Settings(
    /** Mirror the radar's sideways values if cars show on the wrong side. */
    val flip: Boolean = false,
    /** Lane width used to draw lane lines, metres. */
    val laneWidthM: Double = 3.5,
    /** Furthest distance drawn, metres. */
    val maxRangeM: Double = 140.0,
    /** Rider drawn at the top with traffic coming up from below (like Garmin's radar bar). */
    val riderAtTop: Boolean = true,
    /** Rider's distance from the kerb (road edge), metres. */
    val riderOffsetM: Double = 0.5,
    /** Pop-up alert when a vehicle moves in towards the rider. */
    val alertEnabled: Boolean = true,
    /** Only alert for vehicles within this distance, metres. */
    val alertRangeM: Double = 40.0,
    /** Beep with the alert. */
    val alertBeep: Boolean = true,
) {
    companion object {
        private const val PREFS = "varialanes"
        val LANE_WIDTHS = listOf(3.0, 3.3, 3.5, 3.7)
        val RANGES = listOf(80.0, 100.0, 140.0, 175.0)
        val RIDER_OFFSETS = listOf(0.5, 1.0, 1.5)
        val ALERT_RANGES = listOf(25.0, 40.0, 60.0)

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Settings(
                flip = p.getBoolean("flip", false),
                // Round so stored floats match the option lists exactly (3.3f != 3.3).
                laneWidthM = Math.round(p.getFloat("laneWidth", 3.5f) * 10) / 10.0,
                maxRangeM = Math.round(p.getFloat("maxRange", 140f)).toDouble(),
                riderAtTop = p.getBoolean("riderAtTop", true),
                riderOffsetM = Math.round(p.getFloat("riderOffset", 0.5f) * 10) / 10.0,
                alertEnabled = p.getBoolean("alertEnabled", true),
                alertRangeM = Math.round(p.getFloat("alertRange", 40f)).toDouble(),
                alertBeep = p.getBoolean("alertBeep", true),
            )
        }

        fun save(context: Context, s: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("flip", s.flip)
                .putFloat("laneWidth", s.laneWidthM.toFloat())
                .putFloat("maxRange", s.maxRangeM.toFloat())
                .putBoolean("riderAtTop", s.riderAtTop)
                .putFloat("riderOffset", s.riderOffsetM.toFloat())
                .putBoolean("alertEnabled", s.alertEnabled)
                .putFloat("alertRange", s.alertRangeM.toFloat())
                .putBoolean("alertBeep", s.alertBeep)
                .apply()
        }
    }
}
