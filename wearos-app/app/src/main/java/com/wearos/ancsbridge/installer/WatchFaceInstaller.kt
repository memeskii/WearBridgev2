package com.wearos.ancsbridge.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wearos.ancsbridge.filetransfer.FileTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * Manages received files on the watch and coordinates installation
 * of 3rd-party Wear OS watch face .apk packages via PackageInstaller.Session.
 */
class WatchFaceInstaller private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WatchFaceInstaller"

        @Volatile
        private var INSTANCE: WatchFaceInstaller? = null

        fun getInstance(context: Context): WatchFaceInstaller {
            return INSTANCE ?: synchronized(this) {
                val instance = WatchFaceInstaller(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packageInstaller = context.packageManager.packageInstaller

    private val _receivedFiles = MutableStateFlow<List<ReceivedFileItem>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFileItem>> = _receivedFiles.asStateFlow()

    private val fileStatusMap = mutableMapOf<String, String>()
    private val fileErrorMap = mutableMapOf<String, String>()

    init {
        refreshFiles()
    }

    /**
     * Scans transfers directory and updates receivedFiles flow.
     */
    fun refreshFiles() {
        scope.launch {
            try {
                val dir = context.getExternalFilesDir("transfers") ?: File(context.filesDir, "transfers")
                if (!dir.exists()) {
                    dir.mkdirs()
                    _receivedFiles.value = emptyList()
                    return@launch
                }

                val files = dir.listFiles() ?: emptyArray()
                val items = files.map { file ->
                    val status = fileStatusMap[file.name] ?: ReceivedFileItem.STATUS_READY
                    val error = fileErrorMap[file.name]
                    val sha = FileTransferManager.computeFileSha256(file)
                    ReceivedFileItem(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        sizeBytes = file.length(),
                        timestamp = file.lastModified(),
                        isVerified = true,
                        installStatus = status,
                        sha256 = sha,
                        errorMessage = error
                    )
                }.sortedByDescending { it.timestamp }

                _receivedFiles.value = items
                Log.d(TAG, "Refreshed received files: ${items.size} files in transfers/")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing received files: ${e.message}", e)
            }
        }
    }

    /**
     * Checks if the app has permission to install unknown apps.
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Prompts the user to grant unknown app install permission in system Settings.
     */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened ACTION_MANAGE_UNKNOWN_APP_SOURCES for package ${context.packageName}")
        }
    }

    /**
     * Installs a watch face .apk using Android's PackageInstaller.Session API.
     */
    fun installWatchFaceApk(file: File) {
        if (!file.exists() || !file.name.endsWith(".apk", ignoreCase = true)) {
            Log.e(TAG, "Invalid APK file: ${file.absolutePath}")
            updateFileInstallStatus(file.name, ReceivedFileItem.STATUS_FAILED, "File not found or not an APK")
            return
        }

        if (!canInstallPackages()) {
            Log.w(TAG, "Cannot install packages: ACTION_MANAGE_UNKNOWN_APP_SOURCES required")
            requestInstallPermission()
            updateFileInstallStatus(file.name, ReceivedFileItem.STATUS_FAILED, "Unknown sources permission required. Please enable in Settings.")
            return
        }

        scope.launch {
            var session: PackageInstaller.Session? = null
            try {
                updateFileInstallStatus(file.name, ReceivedFileItem.STATUS_INSTALLING, null)

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(null) // determined from APK

                val sessionId = packageInstaller.createSession(params)
                session = packageInstaller.openSession(sessionId)

                FileInputStream(file).use { fis ->
                    session.openWrite("wearbridge_watchface_install", 0, file.length()).use { fos ->
                        fis.copyTo(fos)
                        session.fsync(fos)
                    }
                }

                val intent = Intent(context, WatchFaceInstallReceiver::class.java).apply {
                    action = WatchFaceInstallReceiver.ACTION_INSTALL_STATUS
                    putExtra(WatchFaceInstallReceiver.EXTRA_FILE_NAME, file.name)
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    flags
                )

                session.commit(pendingIntent.intentSender)
                session.close()
                session = null

                Log.i(TAG, "Committed PackageInstaller session #$sessionId for '${file.name}'")
            } catch (e: Exception) {
                Log.e(TAG, "Error installing watch face APK '${file.name}': ${e.message}", e)
                try {
                    session?.abandon()
                } catch (_: Exception) {}
                updateFileInstallStatus(file.name, ReceivedFileItem.STATUS_FAILED, e.message)
            }
        }
    }

    /**
     * Deletes a received file from disk and updates state.
     */
    fun deleteFile(file: File): Boolean {
        val deleted = file.delete()
        fileStatusMap.remove(file.name)
        fileErrorMap.remove(file.name)
        refreshFiles()
        return deleted
    }

    /**
     * Updates install status for a given file name.
     */
    fun updateFileInstallStatus(fileName: String, status: String, error: String?) {
        fileStatusMap[fileName] = status
        if (error != null) {
            fileErrorMap[fileName] = error
        } else {
            fileErrorMap.remove(fileName)
        }

        val updated = _receivedFiles.value.map { item ->
            if (item.fileName == fileName) {
                item.copy(installStatus = status, errorMessage = error)
            } else {
                item
            }
        }
        _receivedFiles.value = updated
    }
}
