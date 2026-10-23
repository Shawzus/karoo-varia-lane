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
 * Draws the lane view for riding on the left (Australia): kerb on the left, the rider's lane, and the
 * overtaking lane to the right. Rider at the top by default with traffic coming up from below.
 * Lane lines are placed from the rider's set distance from the kerb and the set lane width.
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
        val roadShade = if (night) Color.rgb(22, 22, 26) else Color.rgb(242, 242, 242)
        val laneShade = if (night) Color.rgb(28, 30, 52) else Color.rgb(228, 233, 250)
        c.drawColor(bg)

        val pad = min(w, h) * 0.04f
        val textSize = (min(w, h) * 0.085f).coerceIn(12f, 30f)
        val small = textSize * 0.75f
        val sign = if (s.flip) -1.0 else 1.0

        // Status line at the top (the Karoo puts its own buttons over the bottom of the screen).
        val statusBand = textSize * 1.3f
        val areaTop = pad + statusBand
        val areaBottom = h - pad

        // Road: kerb on the left, your lane, then the overtaking lane. Tall and narrow; distance labels
        // in the left margin, speed labels in the right margin.
        text.textSize = small
        val leftMargin = text.measureText("150") + 6
        val rightMargin = text.measureText("+60") + 8
        val roadMetres = 2 * s.laneWidthM
        val roadW = min(w - 2 * pad - leftMargin - rightMargin, (areaBottom - areaTop) * 0.6f).coerceAtLeast(40f)
        val roadLeft = max(pad + leftMargin, (w - roadW) / 2)
        val roadRight = roadLeft + roadW
        val pxPerM = (roadW / roadMetres).toFloat()
        val riderX = roadLeft + (s.riderOffsetM * pxPerM).toFloat()
        val dividerX = roadLeft + (s.laneWidthM * pxPerM).toFloat()
        val riderSize = (pxPerM * 1.1f).coerceIn(10f, h * 0.06f)

        val nearY: Float
        val farY: Float
        val riderTipY: Float
        if (s.riderAtTop) {
            riderTipY = areaTop + 2
            nearY = riderTipY + riderSize * 1.3f
            farY = areaBottom
        } else {
            nearY = areaBottom - riderSize * 1.3f
            farY = areaTop
            riderTipY = nearY + riderSize * 0.2f
        }

        fun xFor(lateralM: Double): Float = riderX + (lateralM * sign).toFloat() * pxPerM
        fun yFor(rangeM: Double): Float = nearY + (rangeM / s.maxRangeM).coerceIn(0.0, 1.0).toFloat() * (farY - nearY)

        // Road surface and your lane
        val roadTop = areaTop
        fill.color = roadShade
        c.drawRect(roadLeft, roadTop, roadRight, h, fill)
        fill.color = laneShade
        c.drawRect(roadLeft, roadTop, dividerX, h, fill)

        // Distance gridlines and labels
        val step = if (s.maxRangeM <= 100) 25.0 else 50.0
        stroke.color = faint
        stroke.strokeWidth = 1f
        stroke.pathEffect = null
        text.color = faint
        text.textSize = small * 0.9f
        text.textAlign = Paint.Align.RIGHT
        var d = step
        while (d < s.maxRangeM) {
            val y = yFor(d)
            c.drawLine(roadLeft, y, roadRight, y, stroke)
            c.drawText("${d.toInt()}", roadLeft - 4, y + text.textSize * 0.35f, text)
            d += step
        }

        // Kerb (solid), lane divider (dashed), far edge of the overtaking lane (thin)
        stroke.color = fg
        stroke.strokeWidth = max(2f, w * 0.008f)
        c.drawLine(roadLeft, roadTop, roadLeft, h, stroke)
        stroke.color = if (night) Color.rgb(140, 140, 140) else Color.rgb(120, 120, 120)
        stroke.strokeWidth = max(1.5f, w * 0.006f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(h * 0.03f, h * 0.025f), 0f)
        c.drawLine(dividerX, roadTop, dividerX, h, stroke)
        stroke.pathEffect = null
        stroke.color = faint
        stroke.strokeWidth = 1.5f
        c.drawLine(roadRight, roadTop, roadRight, h, stroke)

        // Rider (always points up = direction of travel)
        fill.color = RIDER
        val path = Path().apply {
            moveTo(riderX, riderTipY)
            lineTo(riderX - riderSize / 2, riderTipY + riderSize)
            lineTo(riderX + riderSize / 2, riderTipY + riderSize)
            close()
        }
        c.drawPath(path, fill)

        // Vehicles, furthest first so the nearest is drawn on top. Drawn to scale where the field allows.
        val showLabels = h >= 160f
        for (v in frame.vehicles.sortedByDescending { it.target.rangeM }) {
            val t = v.target
            val lengthM = if (t.lengthM > 0) t.lengthM else 4.5
            val widthM = if (t.widthM > 0) t.widthM else 1.9
            val carW = (widthM.toFloat() * pxPerM * 0.6f).coerceIn(roadW * 0.14f, roadW * 0.32f)
            // Length exaggerated (true scale is too small to see) but kept proportional: car ~1.6x its
            // width, a 12 m truck ~2.8x.
            val carH = (carW * (0.9f + lengthM.toFloat() / 4.5f * 0.7f)).coerceAtMost(h * 0.22f)
            // Vehicles beyond the overtaking lane (or left of the kerb) are pinned to the road edge.
            val cx = xFor(t.lateralM).coerceIn(roadLeft + carW / 2 + 1, roadRight - carW / 2 - 1)
            val cy = yFor(t.rangeM)
            val rect = RectF(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
            fill.color = threatColour(v)
            c.drawRoundRect(rect, carW * 0.25f, carW * 0.25f, fill)
            stroke.color = fg
            stroke.strokeWidth = max(1f, w * 0.004f)
            c.drawRoundRect(rect, carW * 0.25f, carW * 0.25f, stroke)

            // Lane-change arrow on the side it's moving towards
            val lateralSpeed = t.speedXMs * sign
            if (abs(lateralSpeed) >= LANE_CHANGE_MS) {
                val dir = if (lateralSpeed > 0) 1f else -1f
                val aw = carW * 0.45f
                val ax = cx + dir * (carW / 2 + 2)
                val arrow = Path().apply {
                    moveTo(ax, cy - carH * 0.28f)
                    lineTo(ax + dir * aw, cy)
                    lineTo(ax, cy + carH * 0.28f)
                    close()
                }
                fill.color = fg
                c.drawPath(arrow, fill)
            }

            // Speed relative to you, in the right margin
            if (showLabels && v.closingSpeedMs >= SAME_SPEED_MS) {
                text.color = fg
                text.textSize = small
                text.textAlign = Paint.Align.LEFT
                c.drawText("+${(v.closingSpeedMs * 3.6).roundToInt()}", roadRight + 4, cy + text.textSize * 0.35f, text)
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
                TrackedVehicle(RadarTarget(id, 23, x, r, len, if (len > 8) 2.5 else 1.9, sx, -closing), closing)
            return RadarRepository.Frame(
                vehicles = listOf(
                    car(1, 1.0, 35.0, 4.5, 0.0, 9.0),
                    car(2, 4.2, 70.0, 12.0, -1.0, 14.0),
                    car(3, 4.5, 115.0, 4.5, 0.0, 1.0),
                ),
                status = RadarRepository.Status(link = RadarRepository.Link.CONNECTED),
            )
        }
    }
}
