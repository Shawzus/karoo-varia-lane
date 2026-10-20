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
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> connectGatt(device)
            BluetoothDevice.BOND_BONDING -> waitForBond()
            else -> {
                // The 820 only sends lane/size data to a paired (bonded) device.
                report(RadarRepository.Link.PAIRING, "Pairing… if it fails, power the 820 on with a long press (purple LED) and retry")
                waitForBond()
                if (!device.createBond()) {
                    // Some stacks bond automatically when we touch a protected characteristic; try connecting anyway.
                    Log.w(TAG, "createBond returned false; connecting anyway")
                    handler.removeCallbacks(bondTimeout)
                    connectGatt(device)
                }
            }
        }
    }

    private fun waitForBond() {
        if (bondReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val device = intent.bluetoothDevice() ?: return
                    if (!device.address.equals(address, ignoreCase = true)) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> handler.post {
                            handler.removeCallbacks(bondTimeout)
                            if (gatt == null) connectGatt(device)
                        }
                        BluetoothDevice.BOND_NONE -> handler.post {
                            handler.removeCallbacks(bondTimeout)
                            report(RadarRepository.Link.PAIRING, "Pairing failed. Long-press the 820 at power-on (purple LED); retrying…")
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
        handler.removeCallbacks(bondTimeout)
        handler.postDelayed(bondTimeout, 45_000)
    }

    private val bondTimeout = Runnable {
        report(RadarRepository.Link.PAIRING, "Pairing timed out; retrying…")
        closeGatt()
        handler.postDelayed({ connectOrPair() }, 5_000)
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (stopped) return
        closeGatt()
        servicesRequested = false
        report(RadarRepository.Link.SEARCHING, "Connecting…")
        gatt = device.connectGatt(
            context, false, callback,
            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, 25_000)
    }

    private val connectTimeout = Runnable {
        Log.i(TAG, "Connect timeout")
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
        handler.removeCallbacks(batteryPoll)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        batteryChar = null
    }

    private fun requestServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        if (!g.discoverServices()) onLinkLost("Service discovery failed")
    }

    // ---- GATT callbacks ------------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                runCatching { g.close() }
                return
            }
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
            if (g === gatt) requestServices(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val radar = g.getService(RADAR_SERVICE)?.getCharacteristic(RADAR_V2)
            if (status != BluetoothGatt.GATT_SUCCESS || radar == null) {
                onLinkLost("Radar service not found. Is this a Varia 820?")
                return
            }
            batteryChar = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
            val cccd = radar.getDescriptor(CCCD)
            if (!g.setCharacteristicNotification(radar, true) || cccd == null) {
                onLinkLost("Couldn't subscribe to radar data")
                return
            }
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                report(RadarRepository.Link.CONNECTED, null)
                handler.post(batteryPoll)
            } else {
                // 5/15/137 = insufficient authentication/encryption: not properly paired.
                Log.w(TAG, "Subscribe failed, status=$status")
                val device = g.device
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    report(RadarRepository.Link.PAIRING, "Radar wants pairing. Long-press the 820 at power-on (purple LED)")
                    closeGatt()
                    waitForBond()
                    device.createBond()
                } else {
                    onLinkLost("Radar refused data (status $status); retrying…")
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
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
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
