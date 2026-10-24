package dev.varialanes.radar

/**
 * Three display sizes. Cut-offs are estimates from typical vehicle dimensions, not from Garmin:
 * - SMALL: bicycles and motorbikes
 * - CAR: cars, utes, SUVs (a Hilux/Ranger is ~5.3 m)
 * - LARGE: trucks, buses, very large pickups (a RAM 1500 is ~5.9 m)
 */
enum class VehicleSize {
    SMALL, CAR, LARGE;

    companion object {
        const val SMALL_MAX_LENGTH_M = 3.0
        const val SMALL_MAX_WIDTH_M = 1.2
        const val LARGE_MIN_LENGTH_M = 5.6
        const val LARGE_MIN_WIDTH_M = 2.2

        /** Unknown (0) dimensions count as a car. */
        fun classify(lengthM: Double, widthM: Double): VehicleSize = when {
            lengthM >= LARGE_MIN_LENGTH_M || widthM >= LARGE_MIN_WIDTH_M -> LARGE
            (lengthM in 0.01..SMALL_MAX_LENGTH_M) || (widthM in 0.01..SMALL_MAX_WIDTH_M) -> SMALL
            else -> CAR
        }
    }
}
