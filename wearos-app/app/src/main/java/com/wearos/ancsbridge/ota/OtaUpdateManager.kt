package com.wearos.ancsbridge.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class OtaState {
    object Idle : OtaState()
    object Checking : OtaState()
    data class Downloading(val progressPercent: Int) : OtaState()
    data class ReadyToInstall(val apkFile: File) : OtaState()
    data class Error(val message: String) : OtaState()
}

/**
 * Manages On-Device Over-The-Air (OTA) APK updates directly on the Wear OS watch.
 * Downloads the latest release APK over Wi-Fi / LTE without requiring ADB or a computer,
 * and launches the native Wear OS package installer.
 */
class OtaUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "OtaUpdateManager"
        const val DEFAULT_UPDATE_URL =
            "https://github.com/memeskii/WearBridgev2/releases/download/latest/WearBridge-WearOS-v1.2-debug.apk"
    }

    private val _otaState = MutableStateFlow<OtaState>(OtaState.Idle)
    val otaState: StateFlow<OtaState> = _otaState.asStateFlow()

    /**
     * Download and install the latest APK directly on the watch.
     */
    suspend fun downloadAndInstallUpdate(apkUrl: String = DEFAULT_UPDATE_URL) {
        withContext(Dispatchers.IO) {
            _otaState.value = OtaState.Checking
            Log.i(TAG, "Starting OTA update from: $apkUrl")

            var connection: HttpURLConnection? = null
            try {
                val url = URL(apkUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "WearBridge-WearOS-Updater")

                // Handle HTTP redirects (GitHub releases redirect to AWS S3)
                var responseCode = connection.responseCode
                var currentUrl = url
                var redirectCount = 0
                while ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER) && redirectCount < 5
                ) {
                    val newLocation = connection?.getHeaderField("Location")
                    Log.i(TAG, "Redirecting to: $newLocation")
                    connection?.disconnect()
                    currentUrl = URL(newLocation)
                    connection = currentUrl.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("User-Agent", "WearBridge-WearOS-Updater")
                    responseCode = connection.responseCode
                    redirectCount++
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errMsg = "HTTP error $responseCode downloading update"
                    Log.e(TAG, errMsg)
                    _otaState.value = OtaState.Error(errMsg)
                    return@withContext
                }

                val totalLength = connection.contentLength
                val updatesDir = File(context.cacheDir, "updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }

                val apkFile = File(updatesDir, "WearBridge-Update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                _otaState.value = OtaState.Downloading(0)
                val input = connection.inputStream
                val output = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (totalLength > 0) {
                        val progress = ((totalBytesRead * 100) / totalLength).toInt()
                        _otaState.value = OtaState.Downloading(progress)
                    }
                }

                output.flush()
                output.close()
                input.close()

                Log.i(TAG, "Downloaded ${totalBytesRead} bytes. APK saved to: ${apkFile.absolutePath}")
                _otaState.value = OtaState.ReadyToInstall(apkFile)

                // Launch installer on main thread
                withContext(Dispatchers.Main) {
                    launchPackageInstaller(apkFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error downloading OTA update: ${e.message}", e)
                _otaState.value = OtaState.Error(e.localizedMessage ?: "Download failed")
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Trigger the Wear OS native package installer dialog for the downloaded APK.
     */
    fun launchPackageInstaller(apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.i(TAG, "Launching package installer for URI: $apkUri")
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            _otaState.value = OtaState.Error("Installer launch failed: ${e.message}")
        }
    }

    fun resetState() {
        _otaState.value = OtaState.Idle
    }
}

