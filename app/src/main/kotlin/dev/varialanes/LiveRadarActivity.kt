package dev.varialanes

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
    private lateinit var stats: RideStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stats = RideStats(this)
        setContentView(LiveRadarView(this, stats))
        RadarRepository.log("live radar opened")
    }

    override fun onStart() {
        super.onStart()
        stats.start()
    }

    override fun onStop() {
        stats.stop()
        super.onStop()
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

private class LiveRadarView(context: Context, private val stats: RideStats) : View(context) {
    private val renderer = LaneRenderer()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
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
        val w = width.toFloat()
        val h = height.toFloat()

        // Stat tiles on the left, radar on the right half.
        val radarW = (w * RADAR_SHARE).coerceAtLeast(150f).coerceAtMost(w)
        val radarLeft = w - radarW
        canvas.save()
        canvas.clipRect(radarLeft, 0f, w, h)
        canvas.translate(radarLeft, 0f)
        renderer.draw(canvas, radarW, h, smoothed, settings, night, flashOn = (now / FLASH_MS) % 2 == 0L)
        canvas.restore()
        if (radarLeft > 80f) drawTiles(canvas, 0f, 0f, radarLeft, h, night)
        postInvalidateDelayed(FRAME_MS)
    }

    private fun drawTiles(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, night: Boolean) {
        val bg = if (night) Color.BLACK else Color.WHITE
        val tileBg = if (night) Color.rgb(28, 28, 28) else Color.rgb(236, 236, 236)
        val fg = if (night) Color.WHITE else Color.BLACK
        val dim = if (night) Color.rgb(160, 160, 160) else Color.rgb(90, 90, 90)
        fill.color = bg
        c.drawRect(left, top, right, bottom, fill)

        val tiles = stats.tiles()
        val gap = 6f
        val tileH = (bottom - top - gap * (tiles.size + 1)) / tiles.size
        val tileW = right - left - gap * 2
        tiles.forEachIndexed { i, t ->
            val x = left + gap
            val y = top + gap + i * (tileH + gap)
            fill.color = tileBg
            c.drawRoundRect(RectF(x, y, x + tileW, y + tileH), 10f, 10f, fill)

            text.typeface = Typeface.DEFAULT_BOLD
            text.color = dim
            text.textAlign = Paint.Align.LEFT
            text.textSize = (tileH * 0.17f).coerceIn(11f, 22f)
            c.drawText(t.label, x + 10, y + text.textSize + 6, text)

            // Big value, shrunk to fit the tile width
            text.color = fg
            text.textSize = tileH * 0.55f
            val unitSize = (tileH * 0.18f).coerceIn(10f, 22f)
            val valueMaxW = tileW - 20 - (if (t.unit.isEmpty()) 0f else unitSize * 2.2f)
            while (text.measureText(t.value) > valueMaxW && text.textSize > 12f) text.textSize *= 0.92f
            val baseline = y + tileH - tileH * 0.14f
            c.drawText(t.value, x + 10, baseline, text)
            if (t.unit.isNotEmpty()) {
                val vw = text.measureText(t.value)
                text.typeface = Typeface.DEFAULT
                text.color = dim
                text.textSize = unitSize
                c.drawText(t.unit, x + 14 + vw, baseline, text)
            }
        }
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
        /** Share of the screen width used by the radar; the rest holds stat tiles. */
        private const val RADAR_SHARE = 0.5f
    }
}
