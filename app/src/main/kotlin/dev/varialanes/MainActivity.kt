package dev.varialanes

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import java.util.Locale
import kotlin.math.roundToInt

/** Settings, Bluetooth permission and a live readout of what the radar is sending. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var permissionButton: Button
    private lateinit var statusView: TextView
    private lateinit var vehiclesView: TextView
    private lateinit var packetsView: TextView
    private lateinit var eventsView: TextView
    private lateinit var laneButton: Button
    private lateinit var offsetButton: Button
    private lateinit var alertRangeButton: Button
    private lateinit var recordingView: TextView
    private val karooSystem by lazy { KarooSystemService(this) }
    private lateinit var rangeButton: Button
    private var settings = Settings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideRecorder.init(this)
        settings = Settings.load(this)
        val pad = (16 * resources.displayMetrics.density).roundToInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(textValue: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = textValue
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 3, 0, pad / 3)
        }.also { column.addView(it) }

        label("Varia Lanes", 22f, bold = true)
        label(
            "BETA, still being tested: don't rely on it for your safety. Keep the Karoo's normal radar alerts on. " +
                "Shows Varia RearVue 820 lane and vehicle size data. Pair it under Sensors → Add sensor → Varia Lanes, " +
                "then add the \"Lane View\" field to a ride page. Keep the 820 paired over ANT+ too so the Karoo's own radar alerts still work.",
        )

        column.addView(
            Button(this).apply {
                text = "Open live radar (full screen)"
                setOnClickListener { startActivity(android.content.Intent(this@MainActivity, LiveRadarActivity::class.java)) }
            },
        )

        permissionButton = Button(this).apply {
            text = "Allow Bluetooth"
            setOnClickListener { requestBluetoothPermissions() }
        }
        column.addView(permissionButton)

        label("Settings", 18f, bold = true)
        column.addView(
            Switch(this).apply {
                text = "You at the top (traffic comes up from below)"
                isChecked = settings.riderAtTop
                setOnCheckedChangeListener { _, checked -> update(settings.copy(riderAtTop = checked)) }
            },
        )
        column.addView(
            Switch(this).apply {
                text = "Flip left/right (use if cars show on the wrong side)"
                isChecked = settings.flip
                setOnCheckedChangeListener { _, checked -> update(settings.copy(flip = checked)) }
            },
        )
        offsetButton = Button(this).apply { setOnClickListener { cycleOffset() } }
        column.addView(offsetButton)
        label("Moving-in alert", 18f, bold = true)
        column.addView(
            Switch(this).apply {
                text = "Pop-up alert when a vehicle moves in towards you"
                isChecked = settings.alertEnabled
                setOnCheckedChangeListener { _, checked -> update(settings.copy(alertEnabled = checked)) }
            },
        )
        alertRangeButton = Button(this).apply { setOnClickListener { cycleAlertRange() } }
        column.addView(alertRangeButton)
        column.addView(
            Switch(this).apply {
                text = "Beep with the alert"
                isChecked = settings.alertBeep
                setOnCheckedChangeListener { _, checked -> update(settings.copy(alertBeep = checked)) }
            },
        )
        column.addView(
            Button(this).apply {
                text = "Test alert"
                setOnClickListener {
                    MoveInAlerter.fire(karooSystem, "TEST: 30 m behind, +40 km/h", settings.alertBeep)
                    RadarRepository.log("test alert sent")
                }
            },
        )
        label("Ride recording", 18f, bold = true)
        column.addView(
            Switch(this).apply {
                text = "Record raw radar data (for troubleshooting)"
                isChecked = settings.recordRides
                setOnCheckedChangeListener { _, checked ->
                    update(settings.copy(recordRides = checked))
                    RideRecorder.enabled = checked
                }
            },
        )
        recordingView = label("").apply { textSize = 12f }

        label("Display", 18f, bold = true)
        laneButton = Button(this).apply { setOnClickListener { cycleLaneWidth() } }
        rangeButton = Button(this).apply { setOnClickListener { cycleRange() } }
        column.addView(laneButton)
        column.addView(rangeButton)
        refreshSettingButtons()

        label("Live radar", 18f, bold = true)
        statusView = label("")
        vehiclesView = label("").apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        label("Bluetooth log (newest first)", 15f, bold = true)
        eventsView = label("").apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        label("Recent raw packets (newest first)", 15f, bold = true)
        packetsView = label("").apply { typeface = Typeface.MONOSPACE; textSize = 11f }

        label("Credits", 18f, bold = true)
        label(
            "Varia 820 Bluetooth protocol reverse-engineered by rale (github.com/rale/radarble); this app's decoder is a port of it. " +
                "Built on Hammerhead's karoo-ext library. Made by Shawzus with Claude Code. " +
                "Unofficial and experimental: keep your normal radar alerts on.",
            13f,
        )

        setContentView(
            ScrollView(this).apply {
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect()
    }

    override fun onStop() {
        karooSystem.disconnect()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionButton()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private val refresher = object : Runnable {
        override fun run() {
            val frame = RadarRepository.frame()
            val st = frame.status
            statusView.text = buildString {
                append("Link: ${st.link}")
                st.message?.let { append("\n$it") }
                append("\nRadar mode: ${st.radarMode ?: "-"}   Packets: ${st.packetCount}")
                st.batteryPercent?.let { append("   Battery: $it%") }
            }
            vehiclesView.text = if (frame.vehicles.isEmpty()) {
                "No vehicles"
            } else {
                "id side_m back_m  len  wid size  cls kmh lat\n" + frame.vehicles.joinToString("\n") { v ->
                    val t = v.target
                    val size = when (dev.varialanes.radar.VehicleSize.classify(t.lengthM, t.widthM)) {
                        dev.varialanes.radar.VehicleSize.SMALL -> "S"
                        dev.varialanes.radar.VehicleSize.CAR -> "C"
                        dev.varialanes.radar.VehicleSize.LARGE -> "L"
                    }
                    String.format(
                        Locale.ROOT, "%-2d %6.1f %6.1f %4.1f %4.1f %4s %4d %3.0f %3d",
                        t.id, t.lateralM, t.rangeM, t.lengthM, t.widthM, size, t.targetClass,
                        v.closingSpeedMs * 3.6, v.laneChange,
                    )
                }
            }
            packetsView.text = RadarRepository.recentPackets().joinToString("\n")
            val files = RideRecorder.files()
            recordingView.text = buildString {
                append(RideRecorder.currentFileName()?.let { "Recording to $it (${RideRecorder.packetsInFile} packets)" } ?: "Not recording right now")
                append("\nSaved recordings: ${files.size}")
                files.take(3).forEach { append("\n  ${it.name}  ${it.length() / 1024} KB") }
                append("\nFolder: ${RideRecorder.folder()?.absolutePath ?: "-"}")
            }
            eventsView.text = RadarRepository.events().joinToString("\n").ifEmpty { "(nothing yet)" }
            handler.postDelayed(this, 500)
        }
    }

    private fun update(new: Settings) {
        settings = new
        Settings.save(this, new)
        refreshSettingButtons()
    }

    private fun cycleAlertRange() {
        val list = Settings.ALERT_RANGES
        update(settings.copy(alertRangeM = list[(list.indexOf(settings.alertRangeM) + 1) % list.size]))
    }

    private fun cycleOffset() {
        val list = Settings.RIDER_OFFSETS
        update(settings.copy(riderOffsetM = list[(list.indexOf(settings.riderOffsetM) + 1) % list.size]))
    }

    private fun cycleLaneWidth() {
        val list = Settings.LANE_WIDTHS
        update(settings.copy(laneWidthM = list[(list.indexOf(settings.laneWidthM) + 1) % list.size]))
    }

    private fun cycleRange() {
        val list = Settings.RANGES
        update(settings.copy(maxRangeM = list[(list.indexOf(settings.maxRangeM) + 1) % list.size]))
    }

    private fun refreshSettingButtons() {
        alertRangeButton.text = "Alert for vehicles within ${settings.alertRangeM.toInt()} m (tap to change)"
        offsetButton.text = when (settings.riderOffsetM) {
            0.5 -> "Your position: at road edge (tap to change)"
            else -> "Your position: ${settings.riderOffsetM} m from road edge (tap to change)"
        }
        laneButton.text = "Lane width: ${settings.laneWidthM} m (tap to change)"
        rangeButton.text = "Distance shown: ${settings.maxRangeM.toInt()} m (tap to change)"
    }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions() = neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestBluetoothPermissions() {
        if (!hasPermissions()) requestPermissions(neededPermissions(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionButton()
    }

    private fun refreshPermissionButton() {
        val ok = hasPermissions()
        permissionButton.text = if (ok) "Bluetooth allowed ✓" else "Allow Bluetooth"
        permissionButton.isEnabled = !ok
        // Ask automatically once; after that the button is there (avoids a loop if denied).
        if (!ok && !autoAsked) {
            autoAsked = true
            requestBluetoothPermissions()
        }
    }

    private var autoAsked = false
}
