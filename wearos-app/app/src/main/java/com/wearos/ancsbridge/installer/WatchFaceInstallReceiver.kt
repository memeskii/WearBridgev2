package com.wearos.ancsbridge.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver listening to PackageInstaller session commit callbacks.
 * Handles system user confirmation (STATUS_PENDING_USER_ACTION), SUCCESS, and FAILURE.
 */
class WatchFaceInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchFaceInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.wearos.ancsbridge.ACTION_INSTALL_STATUS"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_IS_SELF_UPDATE = "extra_is_self_update"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Unknown"
        val isSelfUpdate = intent.getBooleanExtra(EXTRA_IS_SELF_UPDATE, false)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.i(TAG, "Install status received for '$fileName' (selfUpdate=$isSelfUpdate): status=$status, message=$message")

        val installer = WatchFaceInstaller.getInstance(context)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System user confirmation prompt required on Wear OS
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)
                    Log.i(TAG, "Launched PackageInstaller user confirmation prompt for $fileName")
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION received without EXTRA_INTENT")
                    installer.updateFileInstallStatus(fileName, ReceivedFileItem.STATUS_FAILED, "No confirmation intent provided")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Successfully installed watch face package: $fileName")
                installer.updateFileInstallStatus(fileName, ReceivedFileItem.STATUS_INSTALLED, null)
            }

            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val errorMsg = message ?: "Install failed with status code $status"
                Log.e(TAG, "Failed to install watch face '$fileName': $errorMsg")
                installer.updateFileInstallStatus(fileName, ReceivedFileItem.STATUS_FAILED, errorMsg)
            }

            else -> {
                Log.d(TAG, "Intermediate install status $status for $fileName")
            }
        }
    }
}
