package dev.varialanes

import android.content.Context
import android.os.SystemClock
import dev.varialanes.radar.TargetTracker
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pops up a Karoo alert (and beeps) when a vehicle behind starts moving in towards the rider,
 * within the distance set in the app. Checks 4 times a second, independent of the 1 Hz display.
 */
class MoveInAlerter(private val context: Context, private val karooSystem: KarooSystemService) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private val lastAlertById = HashMap<Int, Long>()
    private var lastAlertMs = 0L

    fun start() {
        if (job != null) return
        job = scope.launch {
            var settings = Settings.load(context)
            var settingsLoadedMs = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - settingsLoadedMs > 5_000) {
                    settings = Settings.load(context)
                    settingsLoadedMs = now
                }
                if (settings.closePassAlert) check(settings, now)
                delay(250)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun check(settings: Settings, now: Long) {
        val frame = RadarRepository.frame()
        if (frame.status.link != RadarRepository.Link.CONNECTED) return
        // Only close passes pop up: a vehicle in your lane, closing in, not pulling out.
        // (Moving in / pulling out are shown on screen only.)
        val candidate = frame.vehicles
            .filter { TargetTracker.isClosePassRisk(it) }
            .filter { now - (lastAlertById[it.target.id] ?: 0L) > SAME_VEHICLE_COOLDOWN_MS }
            .minByOrNull { it.target.rangeM }
            ?: return
        if (now - lastAlertMs < ANY_ALERT_COOLDOWN_MS) return
        lastAlertMs = now
        lastAlertById[candidate.target.id] = now
        lastAlertById.entries.removeAll { now - it.value > 60_000 }

        val faster = (candidate.closingSpeedMs * 3.6).roundToInt()
        val detail = buildString {
            append("${candidate.target.rangeM.roundToInt()} m behind")
            if (faster > 0) append(", +$faster km/h")
        }
        RadarRepository.log("ALERT not moving out: $detail")
        fire(karooSystem, detail, settings.alertBeep, title = "NOT MOVING OUT", closePass = true)
    }

    companion object {
        private const val SAME_VEHICLE_COOLDOWN_MS = 8_000L
        private const val ANY_ALERT_COOLDOWN_MS = 3_000L

        /** Shared by the ride alert and the app's Test button. */
        fun fire(
            karooSystem: KarooSystemService,
            detail: String,
            beep: Boolean,
            title: String = "CAR MOVING IN",
            closePass: Boolean = false,
        ) {
            karooSystem.dispatch(
                InRideAlert(
                    id = "varialanes-moving-in",
                    icon = R.drawable.ic_radar,
                    title = title,
                    detail = detail,
                    autoDismissMs = 4_000,
                    backgroundColor = R.color.alert_red,
                    textColor = R.color.alert_text,
                ),
            )
            if (beep && closePass) {
                // Two longer beeps, so it sounds different from "moving in".
                karooSystem.dispatch(
                    PlayBeepPattern(
                        listOf(PlayBeepPattern.Tone(2500, 350), PlayBeepPattern.Tone(null, 120), PlayBeepPattern.Tone(2500, 350)),
                    ),
                )
            } else if (beep) {
                // Three short urgent beeps. Frequency is a guess at what suits the Karoo's buzzer.
                karooSystem.dispatch(
                    PlayBeepPattern(
                        listOf(
                            PlayBeepPattern.Tone(3000, 120), PlayBeepPattern.Tone(null, 70),
                            PlayBeepPattern.Tone(3000, 120), PlayBeepPattern.Tone(null, 70),
                            PlayBeepPattern.Tone(3000, 250),
                        ),
                    ),
                )
            }
        }
    }
}
