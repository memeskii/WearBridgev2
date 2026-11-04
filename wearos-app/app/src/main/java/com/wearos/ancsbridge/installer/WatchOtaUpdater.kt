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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * State representing watch self-update OTA download and installation.
 */
data class OtaUpdateProgress(
    val isUpdating: Boolean = false,
    val progress: Float = 0f,
    val status: String = "IDLE", // "IDLE", "DOWNLOADING", "VERIFYING", "READY_TO_INSTALL", "INSTALLING", "COMPLETED", "FAILED"
    val targetVersion: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedKBps: Double = 0.0,
    val errorMessage: String? = null,
    val downloadedApkFile: File? = null
)

/**
 * Payload sent over Channel 0x01 / 0x07 to trigger high-speed HTTP pull OTA update.
 */
data class OtaCommandPayload(
    val cmd: String,
    val url: String,
    val versionName: String,
    val versionCode: Int = 0,
    val sha256: String = ""
) {
    companion object {
        fun fromJson(jsonStr: String): OtaCommandPayload? {
            return try {
                val json = JSONObject(jsonStr)
                if (json.optString("cmd") != "OTA_UPDATE_URL") return null
                OtaCommandPayload(
                    cmd = json.getString("cmd"),
                    url = json.getString("url"),
                    versionName = json.optString("versionName", "Latest"),
                    versionCode = json.optInt("versionCode", 0),
                    sha256 = json.optString("sha256", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("cmd", cmd)
            put("url", url)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("sha256", sha256)
        }.toString()
    }
}

/**
 * Wear OS Watch App Self-OTA Updater.
 *
 * Supports downloading update APKs directly over Wi-Fi / iPhone Personal Hotspot
 * or handling BLE Channel 0x07 "WATCH_APP_OTA" streams, verifying SHA-256 integrity,
 * and executing in-place self-updates via PackageInstaller.Session targeting
 * context.packageName.
 */
class WatchOtaUpdater private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WatchOtaUpdater"

        @Volatile
        private var INSTANCE: WatchOtaUpdater? = null

        fun getInstance(context: Context): WatchOtaUpdater {
            return INSTANCE ?: synchronized(this) {
                val instance = WatchOtaUpdater(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        /**
         * Checks if target version is newer than current installed version.
         */
        fun isNewerVersion(targetVersion: String, currentVersion: String): Boolean {
            val tClean = targetVersion.removePrefix("v").trim()
            val cClean = currentVersion.removePrefix("v").trim()

            val tParts = tClean.split(".").mapNotNull { it.toIntOrNull() }
            val cParts = cClean.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(tParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val tVal = tParts.getOrElse(i) { 0 }
                val cVal = cParts.getOrElse(i) { 0 }
                if (tVal > cVal) return true
                if (tVal < cVal) return false
            }
            return false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packageInstaller = context.packageManager.packageInstaller

    private val _otaProgress = MutableStateFlow(OtaUpdateProgress())
    val otaProgress: StateFlow<OtaUpdateProgress> = _otaProgress.asStateFlow()

    private var activeDownloadJob: Job? = null

    /**
     * Checks if app has permission to request package installs.
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
            Log.i(TAG, "Opened ACTION_MANAGE_UNKNOWN_APP_SOURCES for self-OTA update")
        }
    }

    /**
     * Parses and handles an inbound OTA command over Channel 0x01 / 0x07.
     */
    fun handleOtaCommand(jsonString: String) {
        val payload = OtaCommandPayload.fromJson(jsonString) ?: run {
            Log.w(TAG, "Ignored unrecognized OTA command: $jsonString")
            return
        }

        Log.i(TAG, "Received OTA_UPDATE_URL: ${payload.url} (v${payload.versionName}, sha256=${payload.sha256})")
        downloadAndInstall(
            urlStr = payload.url,
            targetVersionName = payload.versionName,
            expectedSha256 = payload.sha256,
            versionCode = payload.versionCode
        )
    }

    /**
     * Downloads the APK over HTTP (Wi-Fi or iPhone Hotspot) with live progress tracking,
     * verifies SHA-256, and immediately commits to PackageInstaller.Session.
     */
    fun downloadAndInstall(
        urlStr: String,
        targetVersionName: String,
        expectedSha256: String,
        versionCode: Int = 0
    ) {
        activeDownloadJob?.cancel()

        activeDownloadJob = scope.launch {
            var connection: HttpURLConnection? = null
            var inputStream: BufferedInputStream? = null
            var outputStream: BufferedOutputStream? = null

            try {
                _otaProgress.value = OtaUpdateProgress(
                    isUpdating = true,
                    progress = 0f,
                    status = "DOWNLOADING",
                    targetVersion = targetVersionName
                )

                val otaDir = context.getExternalFilesDir("ota") ?: File(context.filesDir, "ota")
                if (!otaDir.exists()) otaDir.mkdirs()

                val targetFile = File(otaDir, "wearbridge_ota_v${targetVersionName.replace(".", "_")}.apk")
                if (targetFile.exists()) targetFile.delete()

                val url = URL(urlStr)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.requestMethod = "GET"
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("HTTP error $responseCode: ${connection.responseMessage}")
                }

                val totalLength = connection.contentLength.toLong()
                val digest = MessageDigest.getInstance("SHA-256")

                inputStream = BufferedInputStream(connection.inputStream, 32 * 1024)
                outputStream = BufferedOutputStream(FileOutputStream(targetFile), 32 * 1024)

                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L
                val startTime = System.currentTimeMillis()

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val speed = if (elapsedSec > 0.05) (downloadedBytes / 1024.0) / elapsedSec else 0.0
                    val fraction = if (totalLength > 0) (downloadedBytes.toFloat() / totalLength).coerceIn(0f, 1f) else 0f

                    _otaProgress.value = _otaProgress.value.copy(
                        progress = fraction,
                        bytesDownloaded = downloadedBytes,
                        totalBytes = totalLength,
                        speedKBps = speed,
                        status = "DOWNLOADING"
                    )
                }

                outputStream.flush()
                outputStream.close()
                outputStream = null

                // Verify SHA-256
                _otaProgress.value = _otaProgress.value.copy(status = "VERIFYING")
                val computedHash = FileTransferManager.bytesToHex(digest.digest())
                Log.i(TAG, "Downloaded ${targetFile.length()}B. Computed SHA-256: $computedHash, expected: $expectedSha256")

                if (expectedSha256.isNotBlank() && !computedHash.equals(expectedSha256, ignoreCase = true)) {
                    targetFile.delete()
                    throw IllegalStateException("SHA-256 mismatch! Got: $computedHash, expected: $expectedSha256")
                }

                _otaProgress.value = _otaProgress.value.copy(
                    progress = 1.0f,
                    status = "READY_TO_INSTALL",
                    downloadedApkFile = targetFile
                )

                // Launch PackageInstaller
                installOtaApk(targetFile)

            } catch (e: Exception) {
                Log.e(TAG, "OTA Download failed: ${e.message}", e)
                _otaProgress.value = _otaProgress.value.copy(
                    isUpdating = false,
                    status = "FAILED",
                    errorMessage = e.message ?: "Download failed"
                )
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Executes in-place self-update of the WearBridge app via PackageInstaller.Session.
     */
    fun installOtaApk(file: File) {
        if (!file.exists() || !file.name.endsWith(".apk", ignoreCase = true)) {
            Log.e(TAG, "Invalid OTA APK file: ${file.absolutePath}")
            _otaProgress.value = _otaProgress.value.copy(status = "FAILED", errorMessage = "Invalid APK file")
            return
        }

        if (!canInstallPackages()) {
            Log.w(TAG, "Install permission required for OTA self-update")
            requestInstallPermission()
            _otaProgress.value = _otaProgress.value.copy(
                status = "FAILED",
                errorMessage = "Unknown sources permission required. Please enable in Settings."
            )
            return
        }

        scope.launch {
            var session: PackageInstaller.Session? = null
            try {
                _otaProgress.value = _otaProgress.value.copy(
                    status = "INSTALLING",
                    downloadedApkFile = file
                )

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                // Explicitly target our own package to trigger self-update
                params.setAppPackageName(context.packageName)

                val sessionId = packageInstaller.createSession(params)
                session = packageInstaller.openSession(sessionId)

                FileInputStream(file).use { fis ->
                    session.openWrite("wearbridge_self_ota_update", 0, file.length()).use { fos ->
                        fis.copyTo(fos)
                        session.fsync(fos)
                    }
                }

                val intent = Intent(context, WatchFaceInstallReceiver::class.java).apply {
                    action = WatchFaceInstallReceiver.ACTION_INSTALL_STATUS
                    putExtra(WatchFaceInstallReceiver.EXTRA_FILE_NAME, file.name)
                    putExtra(WatchFaceInstallReceiver.EXTRA_IS_SELF_UPDATE, true)
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

                Log.i(TAG, "Committed self-update PackageInstaller session #$sessionId for '${file.name}'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed committing OTA PackageInstaller session: ${e.message}", e)
                try { session?.abandon() } catch (_: Exception) {}
                _otaProgress.value = _otaProgress.value.copy(
                    status = "FAILED",
                    errorMessage = e.message
                )
            }
        }
    }

    /**
     * Cancels an active OTA download or reset state.
     */
    fun cancelOta() {
        activeDownloadJob?.cancel()
        _otaProgress.value = OtaUpdateProgress()
    }
}

