package dev.varialanes

import android.content.Context

data class Settings(
    /** Mirror the radar's sideways values if cars show on the wrong side. */
    val flip: Boolean = false,
    /** Lane width used to draw lane lines, metres. */
    val laneWidthM: Double = 3.5,
    /** Furthest distance drawn, metres. */
    val maxRangeM: Double = 140.0,
) {
    companion object {
        private const val PREFS = "varialanes"
        val LANE_WIDTHS = listOf(3.0, 3.3, 3.5, 3.7)
        val RANGES = listOf(80.0, 100.0, 140.0, 175.0)

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Settings(
                flip = p.getBoolean("flip", false),
                // Round so stored floats match the option lists exactly (3.3f != 3.3).
                laneWidthM = Math.round(p.getFloat("laneWidth", 3.5f) * 10) / 10.0,
                maxRangeM = Math.round(p.getFloat("maxRange", 140f)).toDouble(),
            )
        }

        fun save(context: Context, s: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("flip", s.flip)
                .putFloat("laneWidth", s.laneWidthM.toFloat())
                .putFloat("maxRange", s.maxRangeM.toFloat())
                .apply()
        }
    }
}
