package dev.varialanes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import dev.varialanes.radar.RadarTarget
import dev.varialanes.radar.TargetTracker
import dev.varialanes.radar.TrackedVehicle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Minimal radar: two lanes side by side. Left = behind you (your lane), right = the lane next to you.
 * Each vehicle is a simple marker at its distance, snapped into one of the two lanes (with hysteresis so
 * it doesn't flicker between them). An arrow beside a marker shows a lane change and which way.
 * Nothing else is drawn, apart from a connection message when the radar isn't connected.
 */
class LaneRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tick = 0

    fun render(viewW: Int, viewH: Int, frame: RadarRepository.Frame, settings: Settings, night: Boolean): Bitmap {
        val w = max(viewW, 60)
        val h = max(viewH, 60)
        // Keep the bitmap small: it's sent to the Karoo app every second over IPC.
        val scale = min(1f, sqrt(MAX_PIXELS / (w.toFloat() * h)))
        val bw = max(1, (w * scale).roundToInt())
        val bh = max(1, (h * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.scale(bw / w.toFloat(), bh / h.toFloat())
        tick++
        draw(canvas, w.toFloat(), h.toFloat(), frame, settings, night, flashOn = tick % 2 == 0)
        return bitmap
    }

    /**
     * Draws straight onto a canvas. Used by the 1 Hz ride field (via [render]) and by the full-screen
     * live view. [flashOn] picks which phase of the flashing moving-in marker to show.
     */
    fun draw(c: Canvas, w: Float, h: Float, frame: RadarRepository.Frame, s: Settings, night: Boolean, flashOn: Boolean) {
        val bg = if (night) Color.BLACK else Color.WHITE
        val fg = if (night) Color.WHITE else Color.BLACK
        val lineColour = if (night) Color.rgb(110, 110, 110) else Color.rgb(160, 160, 160)
        c.drawColor(bg)

        val pad = min(w, h) * 0.04f

        if (frame.status.link != RadarRepository.Link.CONNECTED) {
            drawMessage(c, w, h, pad, frame.status, night)
            return
        }

        // Two equal lanes, kept fairly narrow so it reads as a road, centred in the field.
        val roadW = min(w - 2 * pad, (h - 2 * pad) * 0.55f)
        val laneW = roadW / 2
        val roadLeft = (w - roadW) / 2
        val myLaneX = roadLeft + laneW / 2
        val nextLaneX = roadLeft + laneW * 1.5f

        val markerW = laneW * 0.3f
        val markerH = markerW * 1.4f
        val riderSize = laneW * 0.32f
        val nearY = if (s.riderAtTop) pad + riderSize * 1.8f else h - pad - riderSize * 1.8f
        val farY = if (s.riderAtTop) h - pad - markerH / 2 else pad + markerH / 2
        fun yFor(rangeM: Double): Float = nearY + (rangeM / s.maxRangeM).coerceIn(0.0, 1.0).toFloat() * (farY - nearY)

        // Road edges and the lane line
        stroke.pathEffect = null
        stroke.color = lineColour
        stroke.strokeWidth = max(2f, w * 0.008f)
        c.drawLine(roadLeft, 0f, roadLeft, h, stroke)
        c.drawLine(roadLeft + roadW, 0f, roadLeft + roadW, h, stroke)
        stroke.pathEffect = DashPathEffect(floatArrayOf(h * 0.04f, h * 0.03f), 0f)
        c.drawLine(roadLeft + laneW, 0f, roadLeft + laneW, h, stroke)
        stroke.pathEffect = null

        // You
        fill.color = RIDER
        val tipY = if (s.riderAtTop) pad else h - pad - riderSize
        c.drawPath(
            Path().apply {
                moveTo(myLaneX, tipY)
                lineTo(myLaneX - riderSize / 2, tipY + riderSize)
                lineTo(myLaneX + riderSize / 2, tipY + riderSize)
                close()
            },
            fill,
        )

        // Lanes come from the tracker (smoothed, with hysteresis), so screen and alerts always agree.
        for (v in frame.vehicles.sortedByDescending { it.target.rangeM }) {
            if (v.lane < 0) continue
            val t = v.target
            val cx = if (v.lane == 0) myLaneX else nextLaneX
            val cy = yFor(t.rangeM)
            val dir = v.laneChange

            // Red (flashing) = danger in your lane: moving in, or closing in behind you without pulling out.
            val danger = dir < 0 || TargetTracker.isClosePassRisk(v)
            fill.color = if (danger && flashOn) MOVING_IN else fg
            c.drawRoundRect(RectF(cx - markerW / 2, cy - markerH / 2, cx + markerW / 2, cy + markerH / 2), markerW * 0.3f, markerW * 0.3f, fill)

            // Lane-change arrow on the side it's moving to
            if (dir != 0) {
                val base = cx + dir * (markerW / 2 + laneW * 0.05f)
                val aw = laneW * 0.22f
                fill.color = if (dir < 0) MOVING_IN else MOVING_OUT
                c.drawPath(
                    Path().apply {
                        moveTo(base, cy - markerH * 0.4f)
                        lineTo(base + dir * aw, cy)
                        lineTo(base, cy + markerH * 0.4f)
                        close()
                    },
                    fill,
                )
            }
        }
    }

    private fun drawMessage(c: Canvas, w: Float, h: Float, pad: Float, status: RadarRepository.Status, night: Boolean) {
        val msg = status.message ?: when (status.link) {
            RadarRepository.Link.NOT_PAIRED -> "Radar not paired"
            RadarRepository.Link.PAIRING -> "Pairing radar…"
            else -> "Radar searching…"
        }
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT
        text.color = if (night) Color.LTGRAY else Color.DKGRAY
        text.textSize = (min(w, h) * 0.085f).coerceIn(12f, 30f)
        val maxW = w - 2 * pad
        var y = pad
        var remaining = msg
        repeat(4) {
            if (remaining.isEmpty()) return@repeat
            val n = text.breakText(remaining, true, maxW, null)
            var cut = if (n < remaining.length) remaining.lastIndexOf(' ', n).takeIf { it > 0 } ?: n else n
            if (cut <= 0) cut = remaining.length
            y += text.textSize * 1.15f
            c.drawText(remaining.substring(0, cut), pad, y, text)
            remaining = remaining.substring(cut).trimStart()
        }
    }

    companion object {
        private const val MAX_PIXELS = 160_000f

        /** Vehicle moving left, in towards the rider. */
        private val MOVING_IN = Color.rgb(229, 20, 40)
        /** Vehicle moving right, out to pass. */
        private val MOVING_OUT = Color.rgb(25, 118, 210)
        private val RIDER = Color.rgb(30, 136, 229)

        /** Example traffic for the Karoo's page editor preview. */
        fun demoFrame(): RadarRepository.Frame {
            fun car(id: Int, x: Double, r: Double, len: Double, sx: Double, closing: Double) =
                TrackedVehicle(
                    RadarTarget(id, 23, x, r, len, if (len > 8) 2.5 else 1.9, 0.0, -closing), closing,
                    laneChange = if (sx < 0) -1 else if (sx > 0) 1 else 0,
                    lane = if (x > 1.5) 1 else 0,
                )
            return RadarRepository.Frame(
                vehicles = listOf(
                    car(1, 1.0, 35.0, 4.5, 0.0, 9.0),
                    car(2, 3.6, 70.0, 4.5, -1.0, 14.0),
                    car(3, 4.5, 115.0, 2.0, 0.0, 1.0),
                ),
                status = RadarRepository.Status(link = RadarRepository.Link.CONNECTED),
            )
        }
    }
}
