package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * Bluetooth SIG Standard Battery Service (BAS, 0x180F) GATT Server for Wear OS.
 *
 * Exposes the standard Battery Level characteristic (0x2A19) so the native iOS
 * Batteries widget and system settings automatically display the watch's battery level.
 * Also hooks into real-time battery changes to dispatch live telemetry to the iOS Companion App.
 */
@SuppressLint("MissingPermission")
class BatteryGattServer(
    private val context: Context,
    private val onTelemetryDispatch: ((batteryPct: Int, isCharging: Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "BatteryGattServer"

        // Bluetooth SIG standard Battery Service & Characteristic UUIDs
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null

    // Subscribed iOS centrals for 0x2A19 notifications
    private val subscribedCentrals = mutableSetOf<BluetoothDevice>()

    // Current battery & charging state
    var currentBatteryLevel: Int = 100
        private set
    var isCharging: Boolean = false
        private set

    private var isReceiverRegistered = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Central connected to BatteryGattServer: ${device.address}")
                    // Immediate push upon connection so iOS widget doesn't display --%
                    notifyCentral(device, currentBatteryLevel)
                    onTelemetryDispatch?.invoke(currentBatteryLevel, isCharging)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Central disconnected from BatteryGattServer: ${device.address}")
                    synchronized(subscribedCentrals) {
                        subscribedCentrals.remove(device)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                val value = byteArrayOf(currentBatteryLevel.toByte())
                if (offset > value.size) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    return
                }
                val subValue = value.copyOfRange(offset, value.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, subValue)
                Log.d(TAG, "Read Battery Level: $currentBatteryLevel% for ${device.address}")
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val isSubscribed = synchronized(subscribedCentrals) { subscribedCentrals.contains(device) }
                val value = if (isSubscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }

                if (offset > value.size) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    return
                }
                val subValue = value.copyOfRange(offset, value.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, subValue)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val isEnable = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)

                synchronized(subscribedCentrals) {
                    if (isEnable) {
                        subscribedCentrals.add(device)
                        Log.i(TAG, "Central ${device.address} subscribed to Battery notifications")
                    } else {
                        subscribedCentrals.remove(device)
                        Log.i(TAG, "Central ${device.address} unsubscribed from Battery notifications")
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }

                if (isEnable) {
                    // Push immediate value to newly subscribed central
                    notifyCentral(device, currentBatteryLevel)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                processBatteryIntent(intent)
            }
        }
    }

    /**
     * Start the Battery GATT Server and register system battery broadcast listener.
     */
    fun start() {
        if (gattServer != null) {
            Log.w(TAG, "BatteryGattServer already running")
            return
        }

        // 1. Open GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open BluetoothGattServer")
            return
        }

        // 2. Setup standard Bluetooth SIG Battery Service (0x180F)
        val batteryService = BluetoothGattService(
            BATTERY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 0x2A19: Read + Notify, Read permission
        val batteryChar = BluetoothGattCharacteristic(
            BATTERY_LEVEL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // 0x2902: Standard CCCD descriptor (Read + Write permissions)
        val cccdDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        batteryChar.addDescriptor(cccdDescriptor)

        batteryService.addCharacteristic(batteryChar)
        this.batteryLevelCharacteristic = batteryChar

        val serviceAdded = gattServer?.addService(batteryService) ?: false
        Log.i(TAG, "Battery Service (0x180F) added to GATT server: $serviceAdded")

        // 3. Register battery broadcast receiver & read initial sticky state
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = context.registerReceiver(batteryReceiver, filter)
        isReceiverRegistered = true

        if (stickyIntent != null) {
            processBatteryIntent(stickyIntent)
        }
    }

    private fun processBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        if (level >= 0 && scale > 0) {
            val newBatteryPct = (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            val newIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL

            val changed = (newBatteryPct != currentBatteryLevel) || (newIsCharging != isCharging)
            currentBatteryLevel = newBatteryPct
            isCharging = newIsCharging

            if (changed) {
                Log.i(TAG, "Battery level updated: $newBatteryPct% (charging=$newIsCharging)")
                // a) Notify all subscribed centrals via 0x2A19
                notifyAllCentrals(newBatteryPct)
                // b) Dispatch live telemetry to iOS companion app
                onTelemetryDispatch?.invoke(newBatteryPct, newIsCharging)
            }
        }
    }

    /**
     * Notify all subscribed iOS centrals of the updated battery level.
     */
    fun notifyAllCentrals(batteryPct: Int) {
        synchronized(subscribedCentrals) {
            for (device in subscribedCentrals) {
                notifyCentral(device, batteryPct)
            }
        }
    }

    /**
     * Notify a specific central device handling Android 13+ (API 33) vs. legacy fallback.
     */
    private fun notifyCentral(device: BluetoothDevice, batteryPct: Int) {
        val server = gattServer ?: return
        val char = batteryLevelCharacteristic ?: return
        val value = byteArrayOf(batteryPct.toByte())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = server.notifyCharacteristicChanged(device, char, false, value)
            Log.d(TAG, "notifyCharacteristicChanged (API 33+) to ${device.address}: status=$status ($batteryPct%)")
        } else {
            @Suppress("DEPRECATION")
            char.value = value
            @Suppress("DEPRECATION")
            val status = server.notifyCharacteristicChanged(device, char, false)
            Log.d(TAG, "notifyCharacteristicChanged (legacy) to ${device.address}: status=$status ($batteryPct%)")
        }
    }

    /**
     * Trigger immediate telemetry broadcast (e.g. called right when GATT connection opens).
     */
    fun pushCurrentState() {
        notifyAllCentrals(currentBatteryLevel)
        onTelemetryDispatch?.invoke(currentBatteryLevel, isCharging)
    }

    /**
     * Clean up GATT server and unregister broadcast receiver.
     */
    fun stop() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {}
            isReceiverRegistered = false
        }

        synchronized(subscribedCentrals) {
            subscribedCentrals.clear()
        }

        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null
        batteryLevelCharacteristic = null
        Log.i(TAG, "BatteryGattServer stopped")
    }
}
