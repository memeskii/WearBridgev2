package com.wearos.ancsbridge.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.BleScanner
import com.wearos.ancsbridge.model.AncsNotification
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = BleScanner(application)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Observe the service's shared connection state directly
    val connectionState: StateFlow<ConnectionState> = AncsService.sharedConnectionState

    // Observe active live notifications received via ANCS
    val activeNotifications: StateFlow<List<AncsNotification>> = AncsService.activeNotifications

    // Observe live watch battery level and charging state
    val watchBatteryLevel: StateFlow<Int?> = AncsService.watchBatteryLevel
    val isWatchCharging: StateFlow<Boolean> = AncsService.isWatchCharging

    // In-App OTA Update Manager
    val otaManager = com.wearos.ancsbridge.ota.OtaUpdateManager(application)
    val otaState = otaManager.otaState

    fun checkForUpdates(url: String? = null) {
        viewModelScope.launch {
            otaManager.downloadAndInstallUpdate(url ?: com.wearos.ancsbridge.ota.OtaUpdateManager.DEFAULT_UPDATE_URL)
        }
    }

    fun resetOtaState() {
        otaManager.resetState()
    }

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private var scanJob: Job? = null

    val isBluetoothEnabled: Boolean get() = scanner.isBluetoothEnabled

    private val discoveredAppleDevices = mutableMapOf<String, ScanResult>()

    /**
     * Scan for Apple devices for 5 seconds, then connect to the strongest signal.
     * No device list is rendered — the scan runs silently with status updates.
     */
    fun scanAndConnect() {
        if (_isScanning.value) return
        _isScanning.value = true
        _scanStatus.value = "Looking for WearBridge app..."
        discoveredAppleDevices.clear()

        scanJob = viewModelScope.launch {
            // Phase 1: Scan for companion app (10 seconds)
            var found = false
            launch {
                delay(10_000)
                if (_isScanning.value && !found) {
                    _scanStatus.value = "WearBridge app not found.\nOpen it on your iPhone."
                    stopScan()
                }
            }

            try {
                scanner.scanForCompanion().collect { result ->
                    found = true
                    val name = result.device.name ?: result.device.address
                    Log.i("MainViewModel", "Found companion: $name rssi=${result.rssi}")
                    _scanStatus.value = "Found iPhone! Connecting..."
                    stopScan()
                    connectToDevice(result.device.address)
                    return@collect
                }
            } catch (e: Exception) {
                _scanStatus.value = "Scan error: ${e.message}"
                _isScanning.value = false
            }
        }
    }

    private fun connectToBestDevice() {
        if (discoveredAppleDevices.isEmpty()) {
            _scanStatus.value = "No Apple devices found"
            return
        }

        // Pick the device with the strongest signal
        val best = discoveredAppleDevices.values
            .sortedByDescending { it.rssi }
            .first()

        val name = best.device.name ?: best.device.address
        Log.i("MainViewModel", "Connecting to best Apple device: $name rssi=${best.rssi}")
        _scanStatus.value = "Connecting to $name..."
        connectToDevice(best.device.address)
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun connectToDevice(address: String) {
        stopScan()

        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CONNECT
            putExtra(AncsService.EXTRA_DEVICE_ADDRESS, address)
        }
        context.startForegroundService(intent)
    }

    /**
     * Connect directly to a device by its Bluetooth MAC address.
     * Used when we know the iPhone's classic BT address from Settings.
     */
    fun connectByAddress(address: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CONNECT
            putExtra(AncsService.EXTRA_DEVICE_ADDRESS, address)
        }
        context.startForegroundService(intent)
    }

    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun disconnect() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun hasBondedIPhone(): Boolean {
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.bondedDevices.any { device ->
            val name = device.name ?: ""
            name.contains("iPhone", ignoreCase = true) ||
                name.contains("AncsBrid", ignoreCase = true) ||
                name.contains("AncsBridge", ignoreCase = true) ||
                name.contains("WearBridge", ignoreCase = true) ||
                name.contains("WearBrid", ignoreCase = true)
        }
    }

    /**
     * Clear all WearBridge notifications from the watch.
     * Frees up slots so new notifications can come through.
     */
    fun clearAllNotifications() {
        val context = getApplication<Application>()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Cancel all except the foreground service notification (ID 1)
        nm.activeNotifications.forEach { sbn ->
            if (sbn.id != 1) { // service notification
                nm.cancel(sbn.id)
            }
        }
        // Also tell the service to reset its tracking
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CLEAR_ALL
        }
        context.startService(intent)
    }

    /**
     * Send a quick reply to the iOS companion app for a specific notification.
     */
    fun sendQuickReply(notificationUid: Long, replyText: String): Boolean {
        val service = AncsService.instance
        return if (service != null) {
            service.sendQuickReply(notificationUid.toInt(), replyText)
        } else {
            val intent = Intent(getApplication(), AncsService::class.java).apply {
                action = AncsService.ACTION_SEND_QUICK_REPLY
                putExtra(AncsService.EXTRA_NOTIFICATION_UID, notificationUid.toInt())
                putExtra(AncsService.EXTRA_QUICK_REPLY_TEXT, replyText)
            }
            getApplication<Application>().startService(intent)
            true
        }
    }

    /**
     * Dismiss a notification on both watch and iPhone (ActionIDNegative = 1).
     */
    fun dismissNotification(notificationUid: Long) {
        val service = AncsService.instance
        if (service != null) {
            service.dismissNotification(notificationUid)
        } else {
            val intent = Intent(getApplication(), AncsService::class.java).apply {
                action = AncsService.ACTION_DISMISS_NOTIFICATION
                putExtra(AncsService.EXTRA_NOTIFICATION_UID, notificationUid)
            }
            getApplication<Application>().startService(intent)
        }
    }

    /**
     * Called when runtime permissions are granted to re-register health sensors.
     */
    fun onPermissionsGranted() {
        AncsService.instance?.recheckPermissionsAndRegisterSensors()
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
