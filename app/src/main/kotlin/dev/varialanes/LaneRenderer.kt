package dev.varialanes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.varialanes.radar.RadarMode
import dev.varialanes.radar.RadarTarget
import dev.varialanes.radar.TrackedVehicle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws the lane view. Rider at the top by default with traffic coming up from below
 * (setting can put the rider at the bottom instead).
 * Lanes: one to the rider's left, the rider's lane, three to the right (traffic passes on
 * the right in Australia). Lane lines assume the rider is mid-lane, so they're approximate.
 */
class LaneRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

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
        draw(canvas, w.toFloat(), h.toFloat(), frame, settings, night)
        return bitmap
    }

    private fun draw(c: Canvas, w: Float, h: Float, frame: RadarRepository.Frame, s: Settings, night: Boolean) {
        val bg = if (night) Color.BLACK else Color.WHITE
        val fg = if (night) Color.WHITE else Color.BLACK
        val faint = if (night) Color.rgb(70, 70, 70) else Color.rgb(200, 200, 200)
        val laneShade = if (night) Color.rgb(25, 25, 40) else Color.rgb(235, 238, 248)
        c.drawColor(bg)

        val pad = min(w, h) * 0.04f
        val textSize = (min(w, h) * 0.085f).coerceIn(12f, 30f)
        text.textSize = textSize

        val laneUnits = LANES_LEFT + 1 + LANES_RIGHT
        val laneW = (w - 2 * pad) / laneUnits
        val riderX = pad + laneW * (LANES_LEFT + 0.5f)
        val riderSize = (laneW * 0.35f).coerceAtMost(h * 0.07f)
        val sign = if (s.flip) -1.0 else 1.0

        // Status line always at the top: the Karoo overlays its own buttons on the bottom of the screen.
        // Rider at the top: traffic comes up from below. Rider at the bottom: traffic comes down from above.
        val statusBand = textSize * 1.3f
        val areaTop = pad + statusBand
        val areaBottom = h - pad
        val roadTop = areaTop
        val roadBottom = h
        val nearY: Float
        val farY: Float
        val riderTipY: Float
        if (s.riderAtTop) {
            riderTipY = areaTop
            nearY = areaTop + riderSize * 1.2f
            farY = areaBottom
        } else {
            nearY = areaBottom - riderSize * 1.2f
            farY = areaTop
            riderTipY = nearY + riderSize * 0.1f
        }

        fun xFor(lateralM: Double): Float = riderX + (lateralM * sign / s.laneWidthM).toFloat() * laneW
        fun yFor(rangeM: Double): Float = nearY + (rangeM / s.maxRangeM).coerceIn(0.0, 1.0).toFloat() * (farY - nearY)

        // Rider's lane
        fill.color = laneShade
        c.drawRect(riderX - laneW / 2, roadTop, riderX + laneW / 2, roadBottom, fill)

        // Distance gridlines
        val step = if (s.maxRangeM <= 100) 25.0 else 50.0
        stroke.color = faint
        stroke.strokeWidth = 1f
        stroke.pathEffect = null
        text.color = faint
        text.textSize = textSize * 0.7f
        text.textAlign = Paint.Align.LEFT
        var d = step
        while (d < s.maxRangeM) {
            val y = yFor(d)
            c.drawLine(pad, y, w - pad, y, stroke)
            c.drawText("${d.toInt()}", pad + 2, y - 3, text)
            d += step
        }

        // Lane lines (dashed)
        stroke.color = faint
        stroke.strokeWidth = max(1.5f, w * 0.006f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(h * 0.03f, h * 0.025f), 0f)
        for (i in 1 until laneUnits) {
            val x = pad + laneW * i
            c.drawLine(x, roadTop, x, roadBottom, stroke)
        }
        stroke.pathEffect = null

        // Rider (always points up = direction of travel)
        fill.color = RIDER
        val path = Path().apply {
            moveTo(riderX, riderTipY)
            lineTo(riderX - riderSize / 2, riderTipY + riderSize)
            lineTo(riderX + riderSize / 2, riderTipY + riderSize)
            close()
        }
        c.drawPath(path, fill)

        // Vehicles, furthest first so the nearest is drawn on top
        val showLabels = h >= 180f
        for (v in frame.vehicles.sortedByDescending { it.target.rangeM }) {
            val t = v.target
            val cx = xFor(t.lateralM).coerceIn(pad + laneW * 0.25f, w - pad - laneW * 0.25f)
            val cy = yFor(t.rangeM)
            val lengthM = if (t.lengthM > 0) t.lengthM else 4.5
            val widthM = if (t.widthM > 0) t.widthM else 1.9
            val carH = (h * 0.03f + lengthM.toFloat() * h * 0.009f).coerceIn(h * 0.04f, h * 0.15f)
            val carW = (laneW * 0.55f * (widthM / 1.9).toFloat()).coerceIn(laneW * 0.35f, laneW * 0.85f)
            val rect = RectF(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
            fill.color = threatColour(v)
            c.drawRoundRect(rect, carW * 0.2f, carW * 0.2f, fill)
            stroke.color = fg
            stroke.strokeWidth = max(1f, w * 0.004f)
            c.drawRoundRect(rect, carW * 0.2f, carW * 0.2f, stroke)

            // Lane-change arrow
            val lateralSpeed = t.speedXMs * sign
            val aw = laneW * 0.18f
            val arrowExtent = laneW * 0.08f + aw
            var leftEdge = rect.left
            var rightEdge = rect.right
            if (abs(lateralSpeed) >= LANE_CHANGE_MS) {
                val dir = if (lateralSpeed > 0) 1f else -1f
                if (dir < 0) leftEdge -= arrowExtent else rightEdge += arrowExtent
                val ax = cx + dir * (carW / 2 + laneW * 0.08f)
                val arrow = Path().apply {
                    moveTo(ax, cy - carH * 0.3f)
                    lineTo(ax + dir * aw, cy)
                    lineTo(ax, cy + carH * 0.3f)
                    close()
                }
                fill.color = fg
                c.drawPath(arrow, fill)
            }

            // Relative speed label (km/h faster than the rider)
            if (showLabels && v.closingSpeedMs >= SAME_SPEED_MS) {
                text.color = fg
                text.textSize = textSize * 0.75f
                val label = "+${(v.closingSpeedMs * 3.6).roundToInt()}"
                // Left of the vehicle (and its arrow) if it fits, else right
                if (leftEdge - pad > text.measureText(label) + 4) {
                    text.textAlign = Paint.Align.RIGHT
                    c.drawText(label, leftEdge - 4, cy + text.textSize * 0.35f, text)
                } else {
                    text.textAlign = Paint.Align.LEFT
                    c.drawText(label, rightEdge + 4, cy + text.textSize * 0.35f, text)
                }
            }
        }

        // Status line
        text.textSize = textSize
        text.textAlign = Paint.Align.LEFT
        val status = frame.status
        val notConnectedColour = if (night) Color.rgb(170, 170, 170) else Color.rgb(90, 90, 90)
        val (msg, colour) = when {
            // Show the connection code's detailed message so problems can be read off the ride screen.
            status.link != RadarRepository.Link.CONNECTED -> when (status.link) {
                RadarRepository.Link.NOT_PAIRED -> (status.message ?: "Radar not paired") to notConnectedColour
                RadarRepository.Link.PAIRING -> (status.message ?: "Pairing radar…") to AMBER
                else -> (status.message ?: "Radar searching…") to notConnectedColour
            }
            status.radarMode == RadarMode.RADAR_BLOCKED -> "RADAR BLOCKED" to RED
            status.radarMode == RadarMode.LIMP_MODE -> "Radar limp mode" to AMBER
            status.radarMode == RadarMode.UPDATING -> "Radar updating" to AMBER
            frame.vehicles.isEmpty() -> "Clear" to GREEN
            else -> "${frame.vehicles.size} behind" to fg
        }
        text.color = colour
        // Long messages: shrink to 60%, then wrap onto the road area if still too long.
        val maxW = w - 2 * pad
        while (text.measureText(msg) > maxW && text.textSize > textSize * 0.6f) text.textSize *= 0.9f
        var y = pad + textSize * 0.9f
        var remaining = msg
        repeat(3) {
            if (remaining.isEmpty()) return@repeat
            val n = text.breakText(remaining, true, maxW, null)
            var cut = if (n < remaining.length) remaining.lastIndexOf(' ', n).takeIf { it > 0 } ?: n else n
            if (cut <= 0) cut = remaining.length
            c.drawText(remaining.substring(0, cut), pad, y, text)
            remaining = remaining.substring(cut).trimStart()
            y += text.textSize * 1.1f
        }
    }

    private fun threatColour(v: TrackedVehicle): Int {
        val closing = v.closingSpeedMs
        val timeToReach = if (closing > 0.1) v.target.rangeM / closing else Double.MAX_VALUE
        return when {
            closing >= HIGH_THREAT_MS || timeToReach < HIGH_THREAT_TTC_S -> RED
            closing >= SAME_SPEED_MS -> AMBER
            else -> GREY
        }
    }

    companion object {
        private const val MAX_PIXELS = 160_000f
        private const val LANES_LEFT = 1
        private const val LANES_RIGHT = 3

        /** ~40 km/h faster than the rider. Guess, not Garmin's threshold. */
        private const val HIGH_THREAT_MS = 11.0
        private const val HIGH_THREAT_TTC_S = 3.0
        /** Below ~5 km/h faster counts as same speed. */
        private const val SAME_SPEED_MS = 1.5
        private const val LANE_CHANGE_MS = 0.7

        private val RED = Color.rgb(229, 57, 53)
        private val AMBER = Color.rgb(255, 160, 0)
        private val GREEN = Color.rgb(67, 160, 71)
        private val GREY = Color.rgb(140, 140, 140)
        private val RIDER = Color.rgb(30, 136, 229)

        /** Example traffic for the Karoo's page editor preview. */
        fun demoFrame(): RadarRepository.Frame {
            fun car(id: Int, x: Double, r: Double, len: Double, sx: Double, closing: Double) =
                TrackedVehicle(RadarTarget(id, 23, x, r, len, 1.9, sx, -closing), closing)
            return RadarRepository.Frame(
                vehicles = listOf(
                    car(1, 0.0, 35.0, 4.5, 0.0, 9.0),
                    car(2, 3.5, 70.0, 12.0, -1.0, 14.0),
                    car(3, 7.0, 110.0, 4.0, 0.0, 1.0),
                ),
                status = RadarRepository.Status(link = RadarRepository.Link.CONNECTED),
            )
        }
    }
}
