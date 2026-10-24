package dev.varialanes

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import dev.varialanes.radar.TrackedVehicle
import kotlin.math.exp

/**
 * Full-screen radar. Unlike a ride-screen data field (which the Karoo limits to about one redraw a
 * second) this redraws continuously, about 20 times a second, with vehicles gliding smoothly between
 * radar updates. Back button returns to the ride.
 */
class LiveRadarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(LiveRadarView(this))
        RadarRepository.log("live radar opened")
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }
}

private class LiveRadarView(context: Context) : View(context) {
    private val renderer = LaneRenderer()
    private var settings = Settings.load(context)
    private var settingsLoadedMs = 0L
    private var lastDrawMs = 0L

    /** Displayed position per vehicle, eased towards the latest radar position. */
    private val shown = HashMap<Int, Pair<Double, Double>>() // id -> (lateral, range)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (now - settingsLoadedMs > 2_000) {
            settings = Settings.load(context)
            settingsLoadedMs = now
        }
        val dtS = if (lastDrawMs == 0L) 0.0 else ((now - lastDrawMs) / 1000.0).coerceAtMost(0.5)
        lastDrawMs = now

        val frame = RadarRepository.frame()
        val smoothed = frame.copy(vehicles = frame.vehicles.map { smooth(it, dtS) })
        shown.keys.retainAll(frame.vehicles.map { it.target.id }.toSet())

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        renderer.draw(
            canvas, width.toFloat(), height.toFloat(), smoothed, settings, night,
            flashOn = (now / FLASH_MS) % 2 == 0L,
        )
        postInvalidateDelayed(FRAME_MS)
    }

    private fun smooth(v: TrackedVehicle, dtS: Double): TrackedVehicle {
        val t = v.target
        val prev = shown[t.id]
        val next = if (prev == null || dtS <= 0.0) {
            t.lateralM to t.rangeM
        } else {
            // Exponential easing: about 90% of the way to the new position within ~0.3 s.
            val a = 1 - exp(-dtS / EASE_S)
            (prev.first + (t.lateralM - prev.first) * a) to (prev.second + (t.rangeM - prev.second) * a)
        }
        shown[t.id] = next
        return v.copy(target = t.copy(lateralM = next.first, rangeM = next.second))
    }

    companion object {
        private const val FRAME_MS = 50L // ~20 redraws a second
        private const val FLASH_MS = 400L
        private const val EASE_S = 0.12
    }
}
