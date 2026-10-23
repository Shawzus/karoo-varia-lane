package dev.varialanes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dev.varialanes.radar.RadarProtocol
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import java.util.concurrent.ConcurrentHashMap

class VariaLanesExtension : KarooExtension(EXTENSION_ID, "0.1.0") {
    private lateinit var karooSystem: KarooSystemService
    private var alerter: MoveInAlerter? = null
    private val devices = ConcurrentHashMap<String, VariaRadarDevice>()

    override val types by lazy {
        listOf(
            LaneViewDataType(extension),
            CarsBehindDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            if (connected) karooSystem.dispatch(RequestBluetooth(extension))
        }
        alerter = MoveInAlerter(this, karooSystem).also { it.start() }
    }

    override fun onDestroy() {
        alerter?.stop()
        alerter = null
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }

    /** Karoo "Add sensor" screen: list nearby Varia radars. */
    @SuppressLint("MissingPermission")
    override fun startScan(emitter: Emitter<Device>) {
        if (!hasScanPermissions()) {
            Log.w(TAG, "No Bluetooth scan permission; open the Varia Lanes app to grant it")
            return
        }
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner ?: return
        val seen = HashSet<String>()
        val radarService = ParcelUuid.fromString(RadarProtocol.RADAR_SERVICE_UUID)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val advertisesRadar = record?.serviceUuids?.contains(radarService) == true
                val looksLikeVaria = name?.let { n ->
                    listOf("varia", "rearvue", "rtl", "820").any { n.contains(it, ignoreCase = true) }
                } == true
                if (!advertisesRadar && !looksLikeVaria) return
                val address = result.device.address
                if (!seen.add(address)) return
                val device = VariaRadarDevice(this@VariaLanesExtension, extension, address, name)
                devices[device.uid] = device
                emitter.onNext(device.source)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Unfiltered so radars that don't advertise the service UUID are still found by name.
        scanner.startScan(null, settings, callback)
        emitter.setCancellable { runCatching { scanner.stopScan(callback) } }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        val device = devices[uid] ?: VariaRadarDevice.addressFromUid(uid)?.let { address ->
            VariaRadarDevice(this, extension, address, null).also { devices[uid] = it }
        }
        if (device == null) {
            Log.w(TAG, "Unknown device $uid")
            return
        }
        device.connect(emitter)
    }

    private fun hasScanPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    companion object {
        const val EXTENSION_ID = "varialanes"
        private const val TAG = "VariaLanes"
    }
}
