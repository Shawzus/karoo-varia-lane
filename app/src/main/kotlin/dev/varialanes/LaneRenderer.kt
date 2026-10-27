package dev.varialanes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import dev.varialanes.radar.RadarMode
import dev.varialanes.radar.RadarTarget
import dev.varialanes.radar.TrackedVehicle
import dev.varialanes.radar.VehicleSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cadence-style radar: a vertical bar coloured by the overall threat, with each vehicle shown as a
 * white stripe across the bar at its distance, holding a small vehicle icon. A thin line in each stripe
 * is the lane line (kerb on the left, Australia): icon left of it = your lane, right = overtaking lane.
 * Nearest vehicle's distance and speed, and lane-change warnings, are shown large to the left of the bar.
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
     * live view. [flashOn] picks which phase of the flashing moving-in warning to show.
     */
    fun draw(c: Canvas, w: Float, h: Float, frame: RadarRepository.Frame, s: Settings, night: Boolean, flashOn: Boolean) {
        val bg = if (night) Color.BLACK else Color.WHITE
        val fg = if (night) Color.WHITE else Color.BLACK
        c.drawColor(bg)

        val pad = min(w, h) * 0.03f
        val textSize = (min(w, h) * 0.085f).coerceIn(12f, 30f)
        val sign = if (s.flip) -1.0 else 1.0
        val connected = frame.status.link == RadarRepository.Link.CONNECTED
        fun screenDir(v: TrackedVehicle): Int = (v.laneChange * sign).toInt()
        val movingIn = frame.vehicles.filter { screenDir(it) < 0 }.minByOrNull { it.target.rangeM }
        val movingOut = frame.vehicles.filter { screenDir(it) > 0 }.minByOrNull { it.target.rangeM }

        // ---- layout: bar on the right, info panel on the left if there's room ----
        var barW = (w * 0.30f).coerceIn(56f, 170f)
        val labelW = run { text.typeface = Typeface.DEFAULT_BOLD; text.textSize = textSize * 0.8f; text.measureText("150") + 8 }
        var panelW = w - 2 * pad - barW - labelW
        if (panelW < 110f) { // narrow field: bar (and labels if they fit) only
            panelW = 0f
            barW = (w - 2 * pad - labelW).coerceAtLeast(40f)
        }
        val barRight = w - pad
        val barLeft = barRight - barW
        val barTop = pad
        val barBottom = h - pad

        // Vertical scale: rider at the top (near) or bottom.
        val riderH = (barW * 0.16f).coerceIn(10f, 26f)
        val nearY = if (s.riderAtTop) barTop + riderH * 1.6f else barBottom - riderH * 1.6f
        val farY = if (s.riderAtTop) barBottom - pad else barTop + pad
        fun yFor(rangeM: Double): Float = nearY + (rangeM / s.maxRangeM).coerceIn(0.0, 1.0).toFloat() * (farY - nearY)

        // Sideways scale across the bar: kerb at the left edge, two lanes wide.
        val pxPerM = (barW / (2 * s.laneWidthM)).toFloat()
        val riderX = barLeft + (s.riderOffsetM * pxPerM).toFloat()
        val dividerX = barLeft + (s.laneWidthM * pxPerM).toFloat()
        fun xFor(lateralM: Double): Float = riderX + (lateralM * sign).toFloat() * pxPerM

        // ---- bar background: overall threat colour (flashes red while a vehicle moves in) ----
        val barColour = when {
            !connected -> if (night) Color.rgb(60, 60, 60) else Color.rgb(170, 170, 170)
            movingIn != null -> if (flashOn) MOVING_IN else DARK_RED
            frame.vehicles.isEmpty() -> GREEN
            frame.vehicles.any { imminent(it) } -> RED
            frame.vehicles.any { it.closingSpeedMs >= SAME_SPEED_MS } -> ORANGE
            else -> GREY
        }
        fill.color = barColour
        c.drawRect(barLeft, barTop, barRight, barBottom, fill)

        // Distance marks: bold labels left of the bar, tick lines across it.
        val step = if (s.maxRangeM <= 100) 25.0 else 50.0
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = textSize * 0.8f
        text.textAlign = Paint.Align.RIGHT
        text.color = if (night) Color.rgb(210, 210, 210) else Color.rgb(60, 60, 60)
        stroke.color = Color.argb(110, 0, 0, 0)
        stroke.strokeWidth = max(2f, h * 0.003f)
        var d = step
        while (d < s.maxRangeM) {
            val y = yFor(d)
            c.drawLine(barLeft - 4, y, barRight, y, stroke)
            c.drawText("${d.toInt()}", barLeft - 6, y + text.textSize * 0.35f, text)
            d += step
        }

        // Rider marker
        fill.color = Color.WHITE
        val tipY = if (s.riderAtTop) barTop + riderH * 0.3f else barBottom - riderH * 1.3f
        c.drawPath(
            Path().apply {
                moveTo(riderX, tipY); lineTo(riderX - riderH * 0.45f, tipY + riderH); lineTo(riderX + riderH * 0.45f, tipY + riderH); close()
            },
            fill,
        )

        // ---- vehicles: stripe + small icon, furthest first ----
        val stripeH = (h * 0.034f).coerceIn(16f, 44f)
        for (v in frame.vehicles.sortedByDescending { it.target.rangeM }) {
            val t = v.target
            val size = VehicleSize.classify(t.lengthM, t.widthM)
            val bandH = if (size == VehicleSize.LARGE) stripeH * 1.4f else stripeH
            val cy = yFor(t.rangeM)
            val dir = screenDir(v)
            // Moving in: white stripe with a dark outline and red icon/arrow (a red stripe would vanish on
            // the flashing red bar). Moving out: blue stripe.
            fill.color = when {
                dir > 0 -> MOVING_OUT
                night && dir == 0 -> Color.rgb(225, 225, 225)
                else -> Color.WHITE
            }
            c.drawRect(barLeft, cy - bandH / 2, barRight, cy + bandH / 2, fill)
            if (dir < 0) {
                stroke.color = Color.BLACK
                stroke.strokeWidth = max(3f, bandH * 0.12f)
                c.drawRect(barLeft, cy - bandH / 2, barRight, cy + bandH / 2, stroke)
            }
            // Lane line within the stripe
            stroke.color = Color.rgb(90, 90, 90)
            stroke.strokeWidth = max(1.5f, barW * 0.015f)
            c.drawLine(dividerX, cy - bandH / 2, dividerX, cy + bandH / 2, stroke)

            // Icon (front of the vehicle towards the rider)
            val (iw, ih) = when (size) {
                VehicleSize.SMALL -> bandH * 0.26f to bandH * 0.62f
                VehicleSize.CAR -> bandH * 0.46f to bandH * 0.86f
                VehicleSize.LARGE -> bandH * 0.52f to bandH * 0.9f
            }
            val ix = xFor(t.lateralM).coerceIn(barLeft + iw / 2 + 2, barRight - iw / 2 - 2)
            val iconColour = when {
                dir < 0 -> MOVING_IN
                dir > 0 -> Color.WHITE
                else -> Color.BLACK
            }
            drawVehicleIcon(c, ix, cy, iw, ih, iconColour, s.riderAtTop)

            // Lane-change arrow beside the icon
            if (dir != 0) {
                val aw = bandH * 0.6f
                val base = ix + dir * (iw / 2 + 3)
                fill.color = iconColour
                c.drawPath(
                    Path().apply { moveTo(base, cy - bandH * 0.3f); lineTo(base + dir * aw, cy); lineTo(base, cy + bandH * 0.3f); close() },
                    fill,
                )
            }
        }

        // ---- info panel ----
        if (panelW <= 0f) return
        val px = pad
        val maxTextW = panelW - pad
        fun fit(str: String, size: Float, bold: Boolean): Float {
            text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = size
            while (text.measureText(str) > maxTextW && text.textSize > 10f) text.textSize *= 0.92f
            return text.textSize
        }
        text.textAlign = Paint.Align.LEFT
        var y = pad

        val status = frame.status
        if (!connected) {
            val msg = status.message ?: when (status.link) {
                RadarRepository.Link.NOT_PAIRED -> "Radar not paired"
                RadarRepository.Link.PAIRING -> "Pairing radar…"
                else -> "Radar searching…"
            }
            text.color = if (status.link == RadarRepository.Link.PAIRING) AMBER else if (night) Color.LTGRAY else Color.DKGRAY
            fit("x", textSize, false)
            var remaining = msg
            repeat(4) {
                if (remaining.isEmpty()) return@repeat
                val n = text.breakText(remaining, true, maxTextW, null)
                var cut = if (n < remaining.length) remaining.lastIndexOf(' ', n).takeIf { it > 0 } ?: n else n
                if (cut <= 0) cut = remaining.length
                y += text.textSize * 1.15f
                c.drawText(remaining.substring(0, cut), px, y, text)
                remaining = remaining.substring(cut).trimStart()
            }
            return
        }

        val headline: String
        val headColour: Int
        when {
            status.radarMode == RadarMode.RADAR_BLOCKED -> { headline = "RADAR BLOCKED"; headColour = RED }
            movingIn != null -> { headline = "« MOVING IN"; headColour = MOVING_IN }
            movingOut != null -> { headline = "MOVING OUT »"; headColour = MOVING_OUT }
            frame.vehicles.isEmpty() -> { headline = "Clear"; headColour = GREEN }
            else -> { headline = "${frame.vehicles.size} behind"; headColour = fg }
        }
        val alert = movingIn != null
        if (alert && flashOn) { // flashing block behind the headline
            fill.color = MOVING_IN
            c.drawRect(0f, 0f, px + panelW - pad / 2, pad + textSize * 1.9f, fill)
        }
        text.color = if (alert && flashOn) Color.WHITE else headColour
        fit(headline, textSize * 1.2f, true)
        y += text.textSize * 1.1f
        c.drawText(headline, px, y, text)

        // Nearest vehicle (or the one moving in / out): big distance and speed
        val focus = movingIn ?: movingOut ?: frame.vehicles.minByOrNull { it.target.rangeM } ?: return
        val distStr = "${focus.target.rangeM.roundToInt()} m"
        text.color = if (alert) MOVING_IN else fg
        fit(distStr, (panelW * 0.42f).coerceAtMost(h * 0.16f), true)
        y += text.textSize * 1.05f
        c.drawText(distStr, px, y, text)
        val kmh = (focus.closingSpeedMs * 3.6).roundToInt()
        if (kmh >= 3) {
            text.color = fg
            fit("+$kmh km/h", textSize * 1.1f, false)
            y += text.textSize * 1.2f
            c.drawText("+$kmh km/h", px, y, text)
        }
        text.typeface = Typeface.DEFAULT
    }

    /** Small top-down vehicle: outline, windscreen and rear window. Front points towards the rider. */
    private fun drawVehicleIcon(c: Canvas, cx: Float, cy: Float, iw: Float, ih: Float, colour: Int, frontUp: Boolean) {
        val r = RectF(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2)
        stroke.color = colour
        stroke.strokeWidth = max(1.5f, iw * 0.14f)
        c.drawRoundRect(r, iw * 0.35f, iw * 0.35f, stroke)
        fill.color = colour
        val inset = iw * 0.22f
        val front = if (frontUp) r.top + ih * 0.2f else r.bottom - ih * 0.2f
        val rear = if (frontUp) r.bottom - ih * 0.2f else r.top + ih * 0.2f
        val wind = ih * 0.12f
        val rearWin = ih * 0.08f
        c.drawRect(r.left + inset, min(front, front + (if (frontUp) wind else -wind)), r.right - inset, max(front, front + (if (frontUp) wind else -wind)), fill)
        c.drawRect(r.left + inset, min(rear, rear - (if (frontUp) rearWin else -rearWin)), r.right - inset, max(rear, rear - (if (frontUp) rearWin else -rearWin)), fill)
    }

    /** Will reach the rider within a few seconds. */
    private fun imminent(v: TrackedVehicle): Boolean =
        v.closingSpeedMs > 0.1 && v.target.rangeM / v.closingSpeedMs < HIGH_THREAT_TTC_S

    companion object {
        private const val MAX_PIXELS = 160_000f

        private const val HIGH_THREAT_TTC_S = 3.0
        /** Below ~5 km/h faster counts as same speed. */
        private const val SAME_SPEED_MS = 1.5

        /** Vehicle moving left, in towards the rider. */
        private val MOVING_IN = Color.rgb(229, 20, 40)
        /** Vehicle moving right, out to pass. */
        private val MOVING_OUT = Color.rgb(25, 118, 210)

        private val RED = Color.rgb(229, 57, 53)
        private val DARK_RED = Color.rgb(120, 10, 20)
        /** Cadence-style "vehicles approaching" bar colour. */
        private val ORANGE = Color.rgb(242, 154, 56)
        private val AMBER = Color.rgb(255, 160, 0)
        private val GREEN = Color.rgb(67, 160, 71)
        private val GREY = Color.rgb(140, 140, 140)
        private val RIDER = Color.rgb(30, 136, 229)

        /** Example traffic for the Karoo's page editor preview. */
        fun demoFrame(): RadarRepository.Frame {
            fun car(id: Int, x: Double, r: Double, len: Double, sx: Double, closing: Double) =
                TrackedVehicle(
                    RadarTarget(id, 23, x, r, len, if (len > 8) 2.5 else 1.9, sx, -closing), closing,
                    lateralSpeedMs = sx, laneChange = if (sx < 0) -1 else if (sx > 0) 1 else 0,
                )
            return RadarRepository.Frame(
                vehicles = listOf(
                    car(1, 1.0, 35.0, 4.5, 0.0, 9.0),
                    car(2, 4.2, 70.0, 12.0, -1.0, 14.0),
                    car(3, 4.5, 115.0, 2.0, 0.0, 1.0),
                ),
                status = RadarRepository.Status(link = RadarRepository.Link.CONNECTED),
            )
        }
    }
}
