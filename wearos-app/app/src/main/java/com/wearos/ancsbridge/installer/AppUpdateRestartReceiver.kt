package com.wearos.ancsbridge.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wearos.ancsbridge.ancs.AncsService

/**
 * BroadcastReceiver triggered automatically by Android system when the watch app
 * is self-updated (ACTION_MY_PACKAGE_REPLACED).
 * Restarts AncsService foreground service so the watch re-establishes BLE communication
 * and reports its upgraded version over Channel 0x01.
 */
class AppUpdateRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUpdateRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }
            val versionName = pInfo?.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                (pInfo?.versionCode ?: 0).toLong()
            }

            Log.i(TAG, "WearBridge self-update completed successfully! Replaced with v$versionName (code $versionCode)")

            val serviceIntent = Intent(context, AncsService::class.java).apply {
                action = AncsService.ACTION_RECONNECT
            }
            context.startForegroundService(serviceIntent)
        }
    }
}

