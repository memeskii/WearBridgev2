package com.wearos.ancsbridge.ancs

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Restarts AncsService after device boot if a bonded iPhone exists.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            Log.i(TAG, "Boot completed, starting AncsService")
            val serviceIntent = Intent(context, AncsService::class.java).apply {
                action = AncsService.ACTION_RECONNECT
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
