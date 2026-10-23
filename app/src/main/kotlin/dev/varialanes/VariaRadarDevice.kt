package dev.varialanes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.varialanes.radar.RadarProtocol
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import java.util.UUID

/** The 820 as a Karoo sensor. The Karoo calls [connect] when the sensor is enabled. */
class VariaRadarDevice(
    private val context: Context,
    private val extension: String,
    val address: String,
    private val name: String?,
) {
    val uid: String = "$PREFIX-${address.replace(":", "")}"

    val source: Device
        get() = Device(
            extension,
            uid,
            listOf(DataType.dataTypeId(extension, CarsBehindDataType.TYPE_ID)),
            "Varia Lanes ${name ?: address.takeLast(5)}",
        )

    fun connect(emitter: Emitter<DeviceEvent>) {
        val session = GattSession(context.applicationContext, address, uid, extension, emitter)
        session.start()
        emitter.setCancellable { session.stop() }
    }

    companion object {
        const val PREFIX = "varia"

        /** Rebuild the MAC address from a uid like "varia-AABBCCDDEEFF". */
        fun addressFromUid(uid: String): String? {
            val hex = uid.substringAfter("$PREFIX-", "")
            if (hex.length != 12) return null
            return hex.chunked(2).joinToString(":").uppercase()
        }
    }
}

/**
 * One Bluetooth LE session with the radar, with automatic reconnects.
 * All Bluetooth callbacks run on [handler]'s thread, so state needs no locking.
 */
@SuppressLint("MissingPermission")
private class GattSession(
    private val context: Context,
    private val address: String,
    private val sourceUid: String,
    extension: String,
    private val emitter: Emitter<DeviceEvent>,
) {
    private val thread = HandlerThread("varia-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val carsDataTypeId = DataType.dataTypeId(extension, CarsBehindDataType.TYPE_ID)

    @Volatile private var stopped = false
    private var gatt: BluetoothGatt? = null
    private var bondReceiver: BroadcastReceiver? = null
    private var batteryChar: BluetoothGattCharacteristic? = null
    private var radarChar: BluetoothGattCharacteristic? = null
    private var pairingRejections = 0
    private var awaitingBond = false
    private var servicesRequested = false
    private var newNotifyApiSeen = false
    private var newReadApiSeen = false
    private var lastCarsEmitted = -1
    private var lastCarsEmitMs = 0L

    fun start() {
        handler.post { connectOrPair() }
    }

    fun stop() {
        stopped = true
        handler.post {
            handler.removeCallbacksAndMessages(null)
            closeGatt()
            bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            bondReceiver = null
            RadarRepository.setLink(RadarRepository.Link.NOT_PAIRED, "Sensor disabled")
            thread.quitSafely()
        }
    }

    // ---- connection and pairing -------------------------------------------------------------

    private fun connectOrPair() {
        if (stopped) return
        if (!hasConnectPermission()) {
            report(RadarRepository.Link.SEARCHING, "Open the Varia Lanes app on the Karoo and allow Bluetooth")
            handler.postDelayed({ connectOrPair() }, 10_000)
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            report(RadarRepository.Link.SEARCHING, "Bluetooth is off")
            handler.postDelayed({ connectOrPair() }, 5_000)
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run {
            report(RadarRepository.Link.NOT_PAIRED, "Bad address $address")
            return
        }
        // Connect first and only pair when the radar asks for it (same order iPhones use, which the
        // 820 accepts from Cadence). Pairing before connecting was rejected by the 820.
        if (device.bondState == BluetoothDevice.BOND_BONDING) waitForBond() else connectGatt(device)
    }

    /** Pair over the live connection after the radar refused data because we weren't paired. */
    private fun pairOverConnection(device: BluetoothDevice) {
        report(RadarRepository.Link.PAIRING, "Pairing… the 820 must be in pairing mode (purple light)")
        waitForBond()
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            val ok = device.createBond()
            RadarRepository.log("createBond over connection -> $ok")
        }
    }

    private fun waitForBond() {
        if (bondReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val device = intent.bluetoothDevice() ?: return
                    if (!device.address.equals(address, ignoreCase = true)) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    RadarRepository.log("bond state -> ${bondName(state)}")
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> handler.post {
                            awaitingBond = false
                            handler.removeCallbacks(bondTimeout)
                            // Paired over the existing connection: just ask for the data again.
                            val g = gatt
                            if (g != null) subscribeRadar(g) else connectGatt(device)
                        }
                        BluetoothDevice.BOND_NONE -> handler.post {
                            if (!awaitingBond) return@post // not waiting on a pairing
                            awaitingBond = false
                            handler.removeCallbacks(bondTimeout)
                            pairingRejections++
                            report(
                                RadarRepository.Link.PAIRING,
                                "Pairing rejected ($pairingRejections). Turn the 820 off, hold its button 2 s for purple light; retrying…",
                            )
                            closeGatt()
                            handler.postDelayed({ connectOrPair() }, 8_000)
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            bondReceiver = receiver
        }
        awaitingBond = true
        handler.removeCallbacks(bondTimeout)
        handler.postDelayed(bondTimeout, 45_000)
    }

    private val bondTimeout = Runnable {
        awaitingBond = false
        RadarRepository.log("pairing timed out (45 s)")
        report(RadarRepository.Link.PAIRING, "Pairing timed out; retrying…")
        closeGatt()
        handler.postDelayed({ connectOrPair() }, 5_000)
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (stopped) return
        closeGatt()
        servicesRequested = false
        report(RadarRepository.Link.SEARCHING, "Connecting…")
        RadarRepository.log("connect (bond=${bondName(device.bondState)})")
        gatt = device.connectGatt(
            context, false, callback,
            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, 25_000)
    }

    private val connectTimeout = Runnable {
        RadarRepository.log("connect timed out (25 s)")
        onLinkLost("Radar not found. Is it on?")
    }

    private fun onLinkLost(message: String) {
        if (stopped) return
        closeGatt()
        report(RadarRepository.Link.SEARCHING, message)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 3_000)
    }

    private val retry = Runnable { connectOrPair() }

    private fun closeGatt() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(subscribeTimeout)
        handler.removeCallbacks(batteryPoll)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        batteryChar = null
        radarChar = null
    }

    private fun subscribeRadar(g: BluetoothGatt) {
        val radar = radarChar ?: return
        val cccd = radar.getDescriptor(CCCD)
        if (!g.setCharacteristicNotification(radar, true) || cccd == null) {
            onLinkLost("Couldn't subscribe to radar data")
            return
        }
        report(RadarRepository.Link.SEARCHING, "Asking radar for data…")
        val started = writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        RadarRepository.log("subscribe radar -> ${if (started) "sent" else "BUSY, retrying"}")
        handler.removeCallbacks(subscribeTimeout)
        if (!started) {
            handler.postDelayed({ gatt?.let { if (it === g) subscribeRadar(it) } }, 1_000)
            return
        }
        handler.postDelayed(subscribeTimeout, 15_000)
    }

    /** The radar never answered the subscribe request; most likely it's waiting on pairing. */
    private val subscribeTimeout = Runnable {
        val g = gatt ?: return@Runnable
        val device = g.device
        RadarRepository.log("no reply to subscribe (15 s), bond=${bondName(device.bondState)}")
        when (device.bondState) {
            BluetoothDevice.BOND_NONE -> pairOverConnection(device)
            BluetoothDevice.BOND_BONDING -> waitForBond()
            else -> onLinkLost("Radar didn't reply; reconnecting…")
        }
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "none"
        BluetoothDevice.BOND_BONDING -> "pairing"
        BluetoothDevice.BOND_BONDED -> "paired"
        else -> state.toString()
    }

    private fun requestServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        RadarRepository.log("discover services")
        if (!g.discoverServices()) onLinkLost("Service discovery failed")
    }

    // ---- GATT callbacks ------------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                runCatching { g.close() }
                return
            }
            RadarRepository.log("link ${if (newState == BluetoothProfile.STATE_CONNECTED) "up" else "down"} (code $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                handler.removeCallbacks(connectTimeout)
                // Bigger packets so several vehicles fit in one notification. Fall back if no reply.
                if (g.requestMtu(247)) {
                    handler.postDelayed({ if (g === gatt) requestServices(g) }, 3_000)
                } else {
                    requestServices(g)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Disconnected, status=$status")
                onLinkLost("Radar disconnected; reconnecting…")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            RadarRepository.log("mtu $mtu (code $status)")
            if (g === gatt) requestServices(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val radar = g.getService(RADAR_SERVICE)?.getCharacteristic(RADAR_V2)
            RadarRepository.log("services: ${g.services.size} found, radar ${if (radar != null) "yes" else "NO"} (code $status)")
            if (status != BluetoothGatt.GATT_SUCCESS || radar == null) {
                onLinkLost("Radar service not found. Is this a Varia 820?")
                return
            }
            batteryChar = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
            radarChar = radar
            subscribeRadar(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            handler.removeCallbacks(subscribeTimeout)
            RadarRepository.log("subscribe result code $status${if (status == 0) " (OK)" else ""}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pairingRejections = 0
                report(RadarRepository.Link.CONNECTED, null)
                handler.removeCallbacks(batteryPoll)
                handler.post(batteryPoll)
            } else {
                // 5/15/137 = insufficient authentication/encryption: the radar wants us paired.
                Log.w(TAG, "Subscribe failed, status=$status")
                val device = g.device
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED ->
                        onLinkLost("Radar refused data (code $status). Remove and re-add the Varia Lanes sensor; retrying…")
                    BluetoothDevice.BOND_BONDING -> waitForBond() // Android already started pairing
                    else -> pairOverConnection(device)
                }
            }
        }

        // Android 13+ delivers the value directly; older versions use the deprecated overload.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            newNotifyApiSeen = true
            onNotify(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (newNotifyApiSeen) return
            c.value?.let { onNotify(c.uuid, it.copyOf()) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            newReadApiSeen = true
            if (status == BluetoothGatt.GATT_SUCCESS) onRead(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (newReadApiSeen) return
            if (status == BluetoothGatt.GATT_SUCCESS) c.value?.let { onRead(c.uuid, it.copyOf()) }
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        if (uuid != RADAR_V2) return
        val cars = RadarRepository.onRadarNotification(value)
        val now = SystemClock.elapsedRealtime()
        if (cars != lastCarsEmitted || now - lastCarsEmitMs > 1_000) {
            lastCarsEmitted = cars
            lastCarsEmitMs = now
            emitter.onNext(
                OnDataPoint(
                    DataPoint(carsDataTypeId, mapOf(DataType.Field.SINGLE to cars.toDouble()), sourceUid),
                ),
            )
        }
    }

    private fun onRead(uuid: UUID, value: ByteArray) {
        if (uuid == BATTERY_LEVEL && value.isNotEmpty()) {
            val percent = value[0].toInt() and 0xff
            RadarRepository.setBattery(percent)
            emitter.onNext(OnBatteryStatus(BatteryStatus.fromPercentage(percent)))
        }
    }

    private val batteryPoll: Runnable = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            batteryChar?.let { runCatching { g.readCharacteristic(it) } }
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun report(link: RadarRepository.Link, message: String?) {
        RadarRepository.setLink(link, message)
        val status = if (link == RadarRepository.Link.CONNECTED) ConnectionStatus.CONNECTED else ConnectionStatus.SEARCHING
        runCatching { emitter.onNext(OnConnectionStatus(status)) }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    companion object {
        private const val TAG = "VariaLanes"
        val RADAR_SERVICE: UUID = UUID.fromString(RadarProtocol.RADAR_SERVICE_UUID)
        val RADAR_V2: UUID = UUID.fromString(RadarProtocol.RADAR_V2_MEASUREMENT_UUID)
        val BATTERY_SERVICE: UUID = UUID.fromString(RadarProtocol.BATTERY_SERVICE_UUID)
        val BATTERY_LEVEL: UUID = UUID.fromString(RadarProtocol.BATTERY_LEVEL_UUID)
        val CCCD: UUID = UUID.fromString(RadarProtocol.CCCD_UUID)
    }
}

@Suppress("DEPRECATION")
internal fun Intent.bluetoothDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
