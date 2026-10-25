package dev.sarvenaz.biosignalbridge.capture

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Call scan while an Activity is visible. The app owns permission requests and the recording
 * foreground service. A failed connection is closed; reconnecting always needs a new user action.
 */
@SuppressLint("MissingPermission")
class BleCapture(context: Context) : SignalTransport {
    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = applicationContext.getSystemService(BluetoothManager::class.java)
    private val mutableState = MutableStateFlow(CaptureState())
    private val mutableDevices = MutableStateFlow<List<Peripheral>>(emptyList())
    // SUSPEND + tryEmit makes a full queue visible instead of silently dropping its oldest packet.
    private val mutablePackets = MutableSharedFlow<ReceivedPacket>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.SUSPEND
    )
    private val mutableOverflow = MutableStateFlow(0L)
    override val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    override val devices: StateFlow<List<Peripheral>> = mutableDevices.asStateFlow()
    override val packets: SharedFlow<ReceivedPacket> = mutablePackets.asSharedFlow()
    override val overflow: StateFlow<Long> = mutableOverflow.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var activeCharacteristic: BluetoothGattCharacteristic? = null
    private var expectedDescriptor: BluetoothGattDescriptor? = null
    private var scanCallback: ScanCallback? = null
    private var phaseDeadline: Runnable? = null
    private var scanDeadline: Runnable? = null
    private var connectionToken = 0L
    private var closed = false
    private val discovered = linkedMapOf<String, Peripheral>()

    override fun scan() = onMain {
        if (closed) return@onMain
        if (activeGatt != null) {
            mutableState.value = mutableState.value.copy(message = "Disconnect the sensor before scanning")
            return@onMain
        }
        stopScanInternal()
        if (!hasScanPermission()) {
            publish(CapturePhase.ERROR, "Allow nearby device access to scan")
            return@onMain
        }
        if (!hasConnectPermission()) {
            publish(CapturePhase.ERROR, "Allow nearby device access to read sensor names")
            return@onMain
        }
        val adapter = adapterOrError() ?: return@onMain
        if (Build.VERSION.SDK_INT <= 30) {
            val locations = applicationContext.getSystemService(android.location.LocationManager::class.java)
            val enabled = if (Build.VERSION.SDK_INT >= 28) locations?.isLocationEnabled == true
            else locations?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                locations?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
            if (!enabled) {
                publish(CapturePhase.ERROR, "Turn on Location services to scan on this Android version")
                return@onMain
            }
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            publish(CapturePhase.ERROR, "Bluetooth scanning is unavailable")
            return@onMain
        }
        discovered.clear()
        mutableDevices.value = emptyList()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onMain {
                    if (scanCallback !== this) return@onMain
                    runCatching {
                        val device = result.device
                        val peripheral = Peripheral(
                            device.address,
                            result.scanRecord?.deviceName ?: device.name ?: "Bridge sensor",
                            result.rssi
                        )
                        discovered[peripheral.address] = peripheral
                        // A scan is bounded in both time and retained results.
                        if (discovered.size > 64) discovered.remove(discovered.keys.first())
                        mutableDevices.value = discovered.values.sortedByDescending { it.rssi }
                    }.onFailure { scanFailure("Sensor access was interrupted") }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) = onMain {
                if (scanCallback === this) scanFailure("Scan could not start (code $errorCode)")
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            publish(CapturePhase.SCANNING, "Looking for Bridge sensors")
            scanDeadline = Runnable {
                if (scanCallback === callback) {
                    stopScanInternal()
                    publish(CapturePhase.IDLE, "Scan finished · ${mutableDevices.value.size} sensors found")
                }
            }.also { handler.postDelayed(it, SCAN_DURATION_MS) }
        } catch (_: SecurityException) {
            scanFailure("Nearby device access was removed")
        } catch (_: IllegalStateException) {
            scanFailure("Bluetooth is unavailable")
        }
    }

    override fun stopScan() = onMain {
        val wasScanning = scanCallback != null
        stopScanInternal()
        if (wasScanning) publish(CapturePhase.IDLE, "Scan stopped")
    }

    override fun connect(peripheral: Peripheral) = onMain {
        if (closed) return@onMain
        stopScanInternal()
        closeGatt()
        if (!hasConnectPermission()) {
            publish(CapturePhase.ERROR, "Allow nearby device access to connect", peripheral)
            return@onMain
        }
        val adapter = adapterOrError() ?: return@onMain
        if (!BluetoothAdapter.checkBluetoothAddress(peripheral.address)) {
            publish(CapturePhase.ERROR, "This sensor address is invalid", peripheral)
            return@onMain
        }
        mutableOverflow.value = 0
        val token = ++connectionToken
        publish(CapturePhase.CONNECTING, "Connecting to ${peripheral.name}", peripheral)
        val callback = callback(token)
        try {
            activeGatt = adapter.getRemoteDevice(peripheral.address).connectGatt(
                applicationContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE,
                android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK, handler
            )
            if (activeGatt == null) fail("The connection could not be started")
            else deadline(token, CapturePhase.CONNECTING, "The sensor did not respond in time")
        } catch (_: SecurityException) {
            fail("Nearby device access was removed")
        } catch (_: IllegalArgumentException) {
            fail("This sensor address is invalid")
        } catch (_: IllegalStateException) {
            fail("Bluetooth is unavailable")
        }
    }

    override fun disconnect() = onMain {
        stopScanInternal()
        closeGatt()
        if (!closed) publish(CapturePhase.DISCONNECTED, "Sensor disconnected")
    }

    override fun close() = onMain {
        closed = true
        stopScanInternal()
        closeGatt()
        publish(CapturePhase.DISCONNECTED, "Sensor connection closed")
    }

    private fun callback(token: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (!owns(gatt, token)) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("The sensor connection ended (code $status)")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && mutableState.value.phase == CapturePhase.CONNECTING) {
                publish(CapturePhase.DISCOVERING, "Reading sensor services")
                try {
                    if (gatt.discoverServices()) deadline(token, CapturePhase.DISCOVERING, "Service discovery timed out")
                    else fail("The sensor services could not be read")
                } catch (_: SecurityException) {
                    fail("Nearby device access was removed")
                } catch (_: IllegalStateException) {
                    fail("Bluetooth is unavailable")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
                publish(CapturePhase.DISCONNECTED, "The sensor disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = onMain {
            if (!owns(gatt, token) || mutableState.value.phase != CapturePhase.DISCOVERING) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("The sensor services could not be read (code $status)")
                return@onMain
            }
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(NOTIFY_UUID)
            if (characteristic == null || characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                fail("This sensor does not provide the Bridge notification service")
                return@onMain
            }
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                fail("The sensor has no notification control descriptor")
                return@onMain
            }
            activeCharacteristic = characteristic
            expectedDescriptor = descriptor
            publish(CapturePhase.SUBSCRIBING, "Enabling the sensor stream")
            try {
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    fail("The sensor stream could not be enabled")
                    return@onMain
                }
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (accepted) deadline(token, CapturePhase.SUBSCRIBING, "The sensor did not confirm the stream subscription")
                else fail("The stream subscription could not be sent")
            } catch (_: SecurityException) {
                fail("Nearby device access was removed")
            } catch (_: IllegalStateException) {
                fail("Bluetooth is unavailable")
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) = onMain {
            if (!owns(gatt, token)) return@onMain
            // Cached characteristic handles are no longer valid. Reconnection is explicit.
            fail("The sensor services changed. Reconnect the sensor to continue.")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onMain {
            if (!owns(gatt, token) || descriptor !== expectedDescriptor || mutableState.value.phase != CapturePhase.SUBSCRIBING) return@onMain
            if (status != BluetoothGatt.GATT_SUCCESS) fail("The sensor declined the stream subscription (code $status)")
            else {
                cancelDeadline()
                expectedDescriptor = null
                publish(CapturePhase.READY, "Sensor stream ready")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val hostTimeNs = SystemClock.elapsedRealtimeNanos()
            val copy = value.copyOf()
            onMain { receive(gatt, token, characteristic, copy, hostTimeNs) }
        }

        @Deprecated("Legacy callback for Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val hostTimeNs = SystemClock.elapsedRealtimeNanos()
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            onMain { receive(gatt, token, characteristic, copy, hostTimeNs) }
        }
    }

    private fun receive(gatt: BluetoothGatt, token: Long, characteristic: BluetoothGattCharacteristic, value: ByteArray, hostTimeNs: Long) {
        if (!owns(gatt, token) || characteristic !== activeCharacteristic || mutableState.value.phase != CapturePhase.READY) return
        // No subscriber means no recording consumer, and must not look like a successful handoff.
        if (mutablePackets.subscriptionCount.value == 0 || !mutablePackets.tryEmit(ReceivedPacket(value, hostTimeNs))) {
            mutableOverflow.value = if (mutableOverflow.value == Long.MAX_VALUE) Long.MAX_VALUE else mutableOverflow.value + 1
        }
    }

    private fun owns(gatt: BluetoothGatt, token: Long) = !closed && token == connectionToken && gatt === activeGatt

    private fun deadline(token: Long, phase: CapturePhase, message: String) {
        cancelDeadline()
        phaseDeadline = Runnable {
            if (!closed && token == connectionToken && mutableState.value.phase == phase) fail(message)
        }.also { handler.postDelayed(it, CONNECTION_TIMEOUT_MS) }
    }

    private fun cancelDeadline() {
        phaseDeadline?.let(handler::removeCallbacks)
        phaseDeadline = null
    }

    private fun closeGatt() {
        ++connectionToken
        cancelDeadline()
        val gatt = activeGatt
        activeGatt = null
        activeCharacteristic = null
        expectedDescriptor = null
        if (gatt != null) {
            runCatching { if (hasConnectPermission()) gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun stopScanInternal() {
        scanDeadline?.let(handler::removeCallbacks)
        scanDeadline = null
        val callback = scanCallback
        scanCallback = null
        if (callback != null) runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun scanFailure(message: String) {
        stopScanInternal()
        publish(CapturePhase.ERROR, message)
    }

    private fun fail(message: String) {
        closeGatt()
        publish(CapturePhase.ERROR, message)
    }

    private fun publish(phase: CapturePhase, message: String, peripheral: Peripheral? = mutableState.value.peripheral) {
        mutableState.value = CaptureState(phase, peripheral, message)
    }

    private fun adapterOrError(): BluetoothAdapter? {
        val adapter = manager?.adapter
        if (adapter == null) publish(CapturePhase.ERROR, "This device has no Bluetooth adapter")
        else try {
            if (adapter.isEnabled) return adapter
            publish(CapturePhase.ERROR, "Turn on Bluetooth to connect a sensor")
        } catch (_: SecurityException) {
            publish(CapturePhase.ERROR, "Allow nearby device access to use Bluetooth")
        }
        return null
    }

    private fun hasConnectPermission() = Build.VERSION.SDK_INT < 31 ||
        applicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() = applicationContext.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9f7c0001-57ae-4fab-8a12-9c2ed7a064b1")
        val NOTIFY_UUID: UUID = UUID.fromString("9f7c0002-57ae-4fab-8a12-9c2ed7a064b1")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_DURATION_MS = 10_000L
        const val CONNECTION_TIMEOUT_MS = 12_000L
    }
}
