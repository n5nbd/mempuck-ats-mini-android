package com.n5nbd.mempuck.atsmini.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import java.util.UUID

/**
 * Android-framework BLE transport for the ATS Mini Ad hoc mode.
 *
 * The firmware exposes the standard Nordic UART Service. From the Android
 * central's point of view, commands are written to RX (0002) and receiver text
 * is delivered as notifications from TX (0003). Protocol meaning deliberately
 * lives outside this class so the future memory/library model never depends on
 * Bluetooth implementation details.
 */
class AtsBleClient(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onScanState(scanning: Boolean)
        fun onDevice(device: AtsDevice)
        fun onConnecting(device: AtsDevice)
        fun onReady(device: AtsDevice)
        fun onDisconnected()
        fun onPayload(bytes: ByteArray)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var target: AtsDevice? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val stopScanRunnable = Runnable { stopScanInternal() }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            listener.onDevice(
                AtsDevice(
                    address = result.device.address,
                    name = result.scanRecord?.deviceName,
                    rssi = result.rssi,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            stopScanInternal()
            listener.onError("BLE scan failed with Android error $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // A late callback from a connection that has already been replaced
            // must not tear down the current session.
            if (gatt !== this@AtsBleClient.gatt) {
                gatt.close()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("GATT connection failed with status $status")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) {
                        failAndClose("Android could not start GATT service discovery")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> closeConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("GATT service discovery failed with status $status")
                return
            }

            val service: BluetoothGattService = gatt.getService(UART_SERVICE_UUID)
                ?: run {
                    failAndClose("Nordic UART service was not found")
                    return
                }
            rxCharacteristic = service.getCharacteristic(UART_RX_UUID)
            txCharacteristic = service.getCharacteristic(UART_TX_UUID)

            val tx = txCharacteristic
            if (rxCharacteristic == null || tx == null) {
                failAndClose("Nordic UART characteristics were incomplete")
                return
            }

            // Do not send Z? until notification subscription is confirmed. The
            // ATS Mini reply is asynchronous and would otherwise be easy to lose.
            if (!gatt.setCharacteristicNotification(tx, true)) {
                failAndClose("Android rejected UART notification setup")
                return
            }
            val cccd = tx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                ?: run {
                    failAndClose("UART notification descriptor was not found")
                    return
                }
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("UART notification subscription failed with status $status")
                return
            }
            target?.let(listener::onReady)
        }

        @Deprecated("Called on Android 12 and earlier")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == UART_TX_UUID) {
                listener.onPayload(characteristic.value?.copyOf() ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == UART_TX_UUID) listener.onPayload(value.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        stopScanInternal()
        val currentAdapter = adapter
        if (currentAdapter == null) {
            listener.onError("This Android device has no Bluetooth adapter")
            return
        }
        if (!currentAdapter.isEnabled) {
            listener.onError("Bluetooth is turned off")
            return
        }

        val scanner = currentAdapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("Android did not provide a BLE scanner")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UART_SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        scanning = true
        listener.onScanState(true)
        mainHandler.postDelayed(stopScanRunnable, SCAN_WINDOW_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() = stopScanInternal()

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        mainHandler.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
        listener.onScanState(false)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: AtsDevice) {
        stopScanInternal()
        closeConnection()
        val remote = adapter?.getRemoteDevice(device.address)
        if (remote == null) {
            listener.onError("Bluetooth device ${device.address} is unavailable")
            return
        }

        target = device
        listener.onConnecting(device)
        gatt = remote.connectGatt(appContext, false, gattCallback, BluetoothGatt.TRANSPORT_LE)
        if (gatt == null) listener.onError("Android could not create a GATT connection")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        closeConnection()
    }

    @SuppressLint("MissingPermission")
    fun write(bytes: ByteArray): Boolean {
        val currentGatt = gatt ?: return false
        val rx = rxCharacteristic ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(
                rx,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            rx.value = bytes
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(rx)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) failAndClose("Android could not write the notification descriptor")
    }

    @SuppressLint("MissingPermission")
    private fun failAndClose(message: String) {
        listener.onError(message)
        gatt?.disconnect()
        closeConnection()
    }

    @SuppressLint("MissingPermission")
    private fun closeConnection() {
        gatt?.close()
        gatt = null
        target = null
        rxCharacteristic = null
        txCharacteristic = null
        listener.onDisconnected()
    }

    companion object {
        val UART_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_WINDOW_MS = 10_000L
    }
}
