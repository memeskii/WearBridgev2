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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Bluetooth SIG Standard Battery Service (BAS, 0x180F) GATT Server & Telemetry Monitor for Wear OS.
 *
 * Exposes the standard Battery Level characteristic (0x2A19) so the native iOS
 * Batteries widget and system settings automatically display the watch's battery level.
 * Also hooks into real-time battery changes & periodic sampling to dispatch live telemetry
 * ("BATT:<level>:<1|0>") to the iOS Companion App.
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

        private const val PERIODIC_POLL_INTERVAL_MS = 30_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null

    // Subscribed iOS centrals for 0x2A19 notifications
    private val subscribedCentrals = mutableSetOf<BluetoothDevice>()

    // Dedicated scope for periodic battery monitoring & telemetry updates
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicJob: Job? = null

    // Current battery & charging state
    var currentBatteryLevel: Int = -1
        private set
    var isCharging: Boolean = false
        private set

    private var isReceiverRegistered = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Central connected to BatteryGattServer: ${device.address}")
                    // Refresh and push immediately so iOS widget doesn't display --%
                    refreshBatteryState(forceDispatch = true)
                    notifyCentral(device, currentBatteryLevel)
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
                refreshBatteryState(forceDispatch = false)
                val safeLevel = currentBatteryLevel.coerceIn(0, 100)
                val value = byteArrayOf(safeLevel.toByte())
                if (offset > value.size) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                    return
                }
                val subValue = value.copyOfRange(offset, value.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, subValue)
                Log.d(TAG, "Read Battery Level: $safeLevel% for ${device.address}")
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
                    val safeLevel = currentBatteryLevel.coerceIn(0, 100)
                    notifyCentral(device, safeLevel)
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
     * Start the Battery GATT Server, register system battery broadcast listener,
     * and start periodic battery telemetry dispatch.
     */
    fun start() {
        // 1. Immediately read instantaneous hardware battery state
        val (initialPct, initialCharging) = readSystemBattery()
        currentBatteryLevel = initialPct
        isCharging = initialCharging
        Log.i(TAG, "Initial watch battery state: $initialPct% (charging=$initialCharging)")

        // 2. Register broadcast receiver safely across Android 13/14/15
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                ContextCompat.registerReceiver(
                    context,
                    batteryReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                isReceiverRegistered = true
                Log.d(TAG, "Battery broadcast receiver registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register battery receiver with RECEIVER_EXPORTED: ${e.message}")
            }
        }

        // 3. Open GATT Server for standard Bluetooth SIG Battery Service (BAS 0x180F)
        if (gattServer == null) {
            try {
                val server = bluetoothManager.openGattServer(context, gattServerCallback)
                if (server != null) {
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

                    val serviceAdded = server.addService(batteryService)
                    this.gattServer = server
                    Log.i(TAG, "Battery Service (0x180F) added to GATT server: $serviceAdded")
                } else {
                    Log.w(TAG, "BluetoothGattServer could not be opened (GATT server null)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception opening GATT server: ${e.message}")
            }
        }

        // 4. Start periodic background polling ticker
        startPeriodicPolling()

        // 5. Initial telemetry dispatch
        onTelemetryDispatch?.invoke(currentBatteryLevel, isCharging)
    }

    /**
     * Periodically queries the system BatteryManager and dispatches telemetry to iOS.
     * Guarantees companion app receives live battery data even without state change events.
     */
    private fun startPeriodicPolling() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_POLL_INTERVAL_MS)
                try {
                    refreshBatteryState(forceDispatch = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic battery poll error: ${e.message}")
                }
            }
        }
    }

    /**
     * Query the Android system BatteryManager and sticky intent for live battery info.
     */
    private fun readSystemBattery(): Pair<Int, Boolean> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        var pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        var charging = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bm != null) {
            charging = bm.isCharging
        }

        try {
            // Null receiver query reads the current sticky ACTION_BATTERY_CHANGED intent directly
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                if (pct !in 0..100 && level >= 0 && scale > 0) {
                    pct = (level * 100 / scale.toFloat()).toInt()
                }

                val isChargingStatus = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                charging = charging || isChargingStatus
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query sticky battery intent: ${e.message}")
        }

        if (pct !in 0..100) {
            pct = if (currentBatteryLevel in 0..100) currentBatteryLevel else 100
        }

        return Pair(pct.coerceIn(0, 100), charging)
    }

    /**
     * Refreshes the battery state from hardware and dispatches telemetry.
     */
    fun refreshBatteryState(forceDispatch: Boolean = false) {
        val (newBatteryPct, newIsCharging) = readSystemBattery()
        val changed = (newBatteryPct != currentBatteryLevel) || (newIsCharging != isCharging)

        currentBatteryLevel = newBatteryPct
        isCharging = newIsCharging

        if (changed || forceDispatch) {
            Log.i(TAG, "Battery updated: $newBatteryPct% (charging=$newIsCharging, force=$forceDispatch)")
            notifyAllCentrals(newBatteryPct)
            onTelemetryDispatch?.invoke(newBatteryPct, newIsCharging)
        }
    }

    private fun processBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val bmCapacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val newBatteryPct = when {
            bmCapacity in 0..100 -> bmCapacity
            level >= 0 && scale > 0 -> (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            else -> currentBatteryLevel.coerceIn(0, 100)
        }

        val newIsCharging = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bm?.isCharging == true) ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val changed = (newBatteryPct != currentBatteryLevel) || (newIsCharging != isCharging)
        currentBatteryLevel = newBatteryPct
        isCharging = newIsCharging

        if (changed) {
            Log.i(TAG, "Battery broadcast: $newBatteryPct% (charging=$newIsCharging)")
            notifyAllCentrals(newBatteryPct)
            onTelemetryDispatch?.invoke(newBatteryPct, newIsCharging)
        }
    }

    /**
     * Notify all subscribed iOS centrals of the updated battery level.
     */
    fun notifyAllCentrals(batteryPct: Int) {
        val safePct = batteryPct.coerceIn(0, 100)
        synchronized(subscribedCentrals) {
            for (device in subscribedCentrals) {
                notifyCentral(device, safePct)
            }
        }
    }

    /**
     * Notify a specific central device handling Android 13+ (API 33) vs. legacy fallback.
     */
    private fun notifyCentral(device: BluetoothDevice, batteryPct: Int) {
        val server = gattServer ?: return
        val char = batteryLevelCharacteristic ?: return
        val value = byteArrayOf(batteryPct.coerceIn(0, 100).toByte())

        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify central ${device.address}: ${e.message}")
        }
    }

    /**
     * Trigger immediate telemetry broadcast (e.g. called right when GATT connection opens).
     */
    fun pushCurrentState() {
        refreshBatteryState(forceDispatch = true)
    }

    /**
     * Clean up GATT server, unregister broadcast receiver, and stop periodic tasks.
     */
    fun stop() {
        periodicJob?.cancel()
        periodicJob = null

        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {}
            isReceiverRegistered = false
        }

        synchronized(subscribedCentrals) {
            subscribedCentrals.clear()
        }

        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing gattServer: ${e.message}")
        }
        gattServer = null
        batteryLevelCharacteristic = null
        scope.cancel()
        Log.i(TAG, "BatteryGattServer stopped")
    }
}
