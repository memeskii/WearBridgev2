package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.ble.BLEPacketMultiplexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages Wear OS health telemetry harvesting and transmission over BLE Channel 0x04.
 * Responds to "PULL_TELEMETRY" requests from iPhone and performs periodic 1-hour syncs.
 */
class HealthTelemetryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "HealthTelemetryManager"
        private const val SYNC_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

        @Volatile
        private var INSTANCE: HealthTelemetryManager? = null

        fun getInstance(context: Context): HealthTelemetryManager {
            return INSTANCE ?: synchronized(this) {
                val instance = HealthTelemetryManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val repository = HealthTelemetryRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodicSyncJob: Job? = null

    // Outbound chunk sender: (channel, rawData) -> Unit
    var outboundDataDispatcher: ((channel: Byte, data: ByteArray) -> Unit)? = null

    init {
        startPeriodicSync()
    }

    fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                Log.i(TAG, "Periodic 1-hour health telemetry sync triggered")
                syncTelemetryToPhone()
            }
        }
    }

    fun stopPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    /**
     * Harvests latest 24-hour health telemetry and dispatches it over Channel 0x04.
     */
    fun syncTelemetryToPhone(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val payload = repository.harvestTelemetry()
                val jsonBytes = payload.toJsonString().toByteArray(Charsets.UTF_8)
                Log.i(TAG, "Dispatching 24h health telemetry (${jsonBytes.size} bytes) over Channel 0x04")
                outboundDataDispatcher?.invoke(BLEPacketMultiplexer.CHANNEL_HEALTH_TELEMETRY_JSON, jsonBytes)
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error harvesting/dispatching health telemetry: ${e.message}")
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Handles inbound command received on Channel 0x04 (e.g., "PULL_TELEMETRY").
     */
    fun handleInboundCommand(payload: ByteArray) {
        val cmd = String(payload, Charsets.UTF_8).trim()
        Log.i(TAG, "Channel 0x04 inbound command received: '$cmd'")
        if (cmd == "PULL_TELEMETRY" || cmd.contains("PULL_TELEMETRY")) {
            syncTelemetryToPhone()
        }
    }
}

