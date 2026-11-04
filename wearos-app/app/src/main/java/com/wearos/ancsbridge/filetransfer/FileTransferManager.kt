package com.wearos.ancsbridge.filetransfer

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.installer.WatchFaceInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Wear OS High-Throughput File Transfer Engine (Channel 0x07).
 * Receives multi-megabyte .apk / .wff packages and general files streamed
 * with windowed acknowledgments and verifies end-to-end SHA-256 directly on disk.
 */
class FileTransferManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FileTransferManager"

        @Volatile
        private var INSTANCE: FileTransferManager? = null

        fun getInstance(context: Context): FileTransferManager {
            return INSTANCE ?: synchronized(this) {
                val instance = FileTransferManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = "0123456789abcdef"
            val result = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val i = b.toInt() and 0xFF
                result.append(hexChars[i shr 4])
                result.append(hexChars[i and 0x0F])
            }
            return result.toString()
        }

        fun computeFileSha256(file: File): String {
            if (!file.exists()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { fis ->
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            return bytesToHex(digest.digest())
        }

        fun isControlPacket(payload: ByteArray): Boolean {
            if (payload.isEmpty()) return false
            // Check if first non-whitespace byte is '{' and contains "cmd"
            val trimmed = payload.take(64).toByteArray()
            val prefix = String(trimmed, Charsets.UTF_8).trimStart()
            return prefix.startsWith("{") && prefix.contains("\"cmd\"")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transferProgress = MutableStateFlow(FileTransferProgress())
    val transferProgress: StateFlow<FileTransferProgress> = _transferProgress.asStateFlow()

    // Active transfer state
    private var activeTransferId: String? = null
    private var activeFileName: String? = null
    private var activeFileSize: Long = 0L
    private var activeFileType: String = FileTransferConstants.FILE_TYPE_WATCH_FACE
    private var expectedSha256: String? = null
    private var currentWindowIndex: Int = 0
    private var totalWindows: Int = 0
    private var bytesReceived: Long = 0L
    private var transferStartTimeMs: Long = 0L

    private var activeFile: File? = null
    private var outputStream: BufferedOutputStream? = null
    private var messageDigest: MessageDigest? = null
    private var watchdogJob: Job? = null

    // Callback to send packets back over Channel 0x07
    var outboundPacketSender: ((ByteArray) -> Unit)? = null

    private fun resetWatchdog(timeoutMs: Long = 60_000L) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(timeoutMs)
            if (activeTransferId != null) {
                Log.w(TAG, "File transfer 60-second watchdog expired with no incoming packets. Cleaning up.")
                _transferProgress.value = _transferProgress.value.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = "Transfer timed out (60s watchdog)"
                )
                cleanupSession()
            }
        }
    }

    /**
     * Entry point for incoming Channel 0x07 payloads.
     */
    fun handleIncomingChannel07Payload(payload: ByteArray) {
        if (isControlPacket(payload)) {
            handleControlPacket(payload)
        } else {
            handleBinaryWindow(payload)
        }
    }

    private fun handleControlPacket(payload: ByteArray) {
        try {
            val jsonStr = String(payload, Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val cmd = json.optString("cmd")

            when (cmd) {
                FileTransferConstants.CMD_FILE_START -> {
                    val startPayload = FileStartPayload.fromJson(jsonStr)
                    if (startPayload != null) {
                        onFileStart(startPayload)
                    }
                }
                FileTransferConstants.CMD_FILE_END -> {
                    val transferId = json.optString("transferId")
                    onFileEnd(transferId)
                }
                FileTransferConstants.CMD_FILE_CANCEL -> {
                    val transferId = json.optString("transferId")
                    onFileCancel(transferId)
                }
                FileTransferConstants.CMD_OTA_UPDATE_URL -> {
                    Log.i(TAG, "OTA_UPDATE_URL received over FileTransfer control channel")
                    com.wearos.ancsbridge.installer.WatchOtaUpdater.getInstance(context).handleOtaCommand(jsonStr)
                }
                else -> {
                    Log.d(TAG, "Unhandled control command: $cmd")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing control packet: ${e.message}", e)
        }
    }

    private fun onFileStart(payload: FileStartPayload) {
        scope.launch {
            try {
                // Cancel existing session if any
                cleanupSession()

                val transfersDir = context.getExternalFilesDir("transfers") ?: File(context.filesDir, "transfers")
                if (!transfersDir.exists()) {
                    transfersDir.mkdirs()
                }

                val targetFile = File(transfersDir, payload.fileName)
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                activeTransferId = payload.transferId
                activeFileName = payload.fileName
                activeFileSize = payload.fileSize
                activeFileType = payload.fileType
                expectedSha256 = payload.sha256
                totalWindows = payload.totalWindows
                currentWindowIndex = 0
                bytesReceived = 0L
                transferStartTimeMs = System.currentTimeMillis()
                activeFile = targetFile

                outputStream = BufferedOutputStream(FileOutputStream(targetFile), FileTransferConstants.WINDOW_SIZE_BYTES)
                messageDigest = MessageDigest.getInstance("SHA-256")

                _transferProgress.value = FileTransferProgress(
                    transferId = payload.transferId,
                    fileName = payload.fileName,
                    fileSize = payload.fileSize,
                    bytesTransferred = 0L,
                    progress = 0f,
                    speedKBps = 0.0,
                    status = TransferStatus.RECEIVING
                )

                Log.i(TAG, "Initialized file transfer for '${payload.fileName}' (${payload.fileSize} bytes, ${payload.totalWindows} windows, sha256=${payload.sha256})")
                resetWatchdog()

                // Send window ACK for initialization (windowIndex = -1)
                val initAck = FileWindowAckPayload(
                    transferId = payload.transferId,
                    windowIndex = -1,
                    bytesReceived = 0L,
                    progress = 0.0
                )
                sendControlPayload(initAck.toJson().toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize file transfer: ${e.message}", e)
                _transferProgress.value = _transferProgress.value.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = e.message
                )
                cleanupSession()
            }
        }
    }

    private fun handleBinaryWindow(windowBytes: ByteArray) {
        val transferId = activeTransferId ?: run {
            Log.d(TAG, "Received binary chunk with no active transfer session (${windowBytes.size} bytes)")
            return
        }

        val out = outputStream ?: run {
            Log.e(TAG, "Output stream is null for transfer $transferId")
            return
        }

        val digest = messageDigest ?: run {
            Log.e(TAG, "Message digest is null for transfer $transferId")
            return
        }

        scope.launch {
            try {
                // Write directly to disk & update SHA-256 digest
                out.write(windowBytes)
                digest.update(windowBytes)
                bytesReceived += windowBytes.size

                val now = System.currentTimeMillis()
                val elapsedSec = (now - transferStartTimeMs) / 1000.0
                val speed = if (elapsedSec > 0.001) (bytesReceived / 1024.0) / elapsedSec else 0.0
                val progressFraction = if (activeFileSize > 0) (bytesReceived.toDouble() / activeFileSize).coerceIn(0.0, 1.0) else 0.0

                _transferProgress.value = _transferProgress.value.copy(
                    bytesTransferred = bytesReceived,
                    progress = progressFraction.toFloat(),
                    speedKBps = speed,
                    status = TransferStatus.RECEIVING
                )

                val ackWindowIndex = currentWindowIndex
                currentWindowIndex++
                resetWatchdog()

                // Send window ACK
                val ack = FileWindowAckPayload(
                    transferId = transferId,
                    windowIndex = ackWindowIndex,
                    bytesReceived = bytesReceived,
                    progress = progressFraction
                )
                sendControlPayload(ack.toJson().toByteArray(Charsets.UTF_8))
                Log.d(TAG, "Acknowledged window $ackWindowIndex: $bytesReceived / $activeFileSize bytes (${String.format("%.1f", progressFraction * 100)}% @ ${String.format("%.1f", speed)} KB/s)")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing window data: ${e.message}", e)
                _transferProgress.value = _transferProgress.value.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = e.message
                )
                cleanupSession()
            }
        }
    }

    private fun onFileEnd(transferId: String) {
        if (activeTransferId != transferId) {
            Log.w(TAG, "Mismatch transferId on FILE_END: expected $activeTransferId, got $transferId")
            return
        }

        scope.launch {
            try {
                _transferProgress.value = _transferProgress.value.copy(status = TransferStatus.VERIFYING)

                outputStream?.flush()
                outputStream?.close()
                outputStream = null

                val digest = messageDigest ?: throw IllegalStateException("Digest is null")
                val computedHash = bytesToHex(digest.digest())
                val expected = expectedSha256 ?: ""

                val verified = computedHash.equals(expected, ignoreCase = true)
                val status = if (verified) FileTransferConstants.STATUS_VERIFIED else FileTransferConstants.STATUS_HASH_MISMATCH
                val savedFile = activeFile
                val savedPath = savedFile?.absolutePath

                Log.i(TAG, "File transfer end: computed sha256=$computedHash, expected=$expected -> verification=$status")

                if (!verified) {
                    savedFile?.delete()
                    _transferProgress.value = _transferProgress.value.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = "SHA-256 hash mismatch! Computed: $computedHash, expected: $expected"
                    )
                } else {
                    _transferProgress.value = _transferProgress.value.copy(
                        progress = 1.0f,
                        status = TransferStatus.COMPLETED,
                        savedPath = savedPath
                    )
                    if (activeFileType == FileTransferConstants.FILE_TYPE_WATCH_APP_OTA) {
                        Log.i(TAG, "Transfer completed for WATCH_APP_OTA, triggering self-update")
                        savedFile?.let {
                            com.wearos.ancsbridge.installer.WatchOtaUpdater.getInstance(context).installOtaApk(it)
                        }
                    } else {
                        // Refresh watch face installer file catalog
                        WatchFaceInstaller.getInstance(context).refreshFiles()
                    }
                }

                // Return FILE_COMPLETE
                val completePayload = FileCompletePayload(
                    transferId = transferId,
                    status = status,
                    savedPath = if (verified) savedPath else null
                )
                sendControlPayload(completePayload.toJson().toByteArray(Charsets.UTF_8))

                cleanupSession(preserveFile = verified)
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing file transfer: ${e.message}", e)
                _transferProgress.value = _transferProgress.value.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = e.message
                )
                cleanupSession()
            }
        }
    }

    private fun onFileCancel(transferId: String) {
        if (activeTransferId == transferId || transferId.isEmpty()) {
            Log.i(TAG, "Transfer $transferId cancelled by peer")
            scope.launch {
                _transferProgress.value = _transferProgress.value.copy(
                    status = TransferStatus.CANCELLED,
                    errorMessage = "Transfer cancelled"
                )
                cleanupSession()
            }
        }
    }

    fun cancelActiveTransfer() {
        val id = activeTransferId ?: return
        scope.launch {
            val cancelPayload = JSONObject().apply {
                put("cmd", FileTransferConstants.CMD_FILE_CANCEL)
                put("transferId", id)
            }.toString().toByteArray(Charsets.UTF_8)
            sendControlPayload(cancelPayload)

            _transferProgress.value = _transferProgress.value.copy(
                status = TransferStatus.CANCELLED,
                errorMessage = "Cancelled by user"
            )
            cleanupSession()
        }
    }

    private fun sendControlPayload(data: ByteArray) {
        outboundPacketSender?.invoke(data)
    }

    private fun cleanupSession(preserveFile: Boolean = false) {
        watchdogJob?.cancel()
        watchdogJob = null

        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null
        messageDigest = null

        if (!preserveFile && activeFile != null && activeFile!!.exists()) {
            activeFile!!.delete()
        }

        activeTransferId = null
        activeFileName = null
        activeFileSize = 0L
        expectedSha256 = null
        currentWindowIndex = 0
        totalWindows = 0
        bytesReceived = 0L
        activeFile = null
    }
}
