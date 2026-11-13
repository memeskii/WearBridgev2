package com.wearos.ancsbridge.filetransfer

import android.content.Context
import android.util.Log
import android.os.SystemClock
import android.system.Os
import com.wearos.ancsbridge.installer.WatchFaceInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
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
                INSTANCE?.let { return it }
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
            if (payload[0] == FileTransferConstants.HEADER_CONTROL_JSON) return true
            // Check if first non-whitespace byte is '{' and contains "cmd"
            val trimmed = payload.take(64).toByteArray()
            val prefix = String(trimmed, Charsets.UTF_8).trimStart()
            return prefix.startsWith("{") && prefix.contains("\"cmd\"")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val incoming = Channel<ByteArray>(capacity = 16)

    init {
        scope.launch {
            for (payload in incoming) processIncomingPayload(payload)
        }
    }

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
    private var destinationFile: File? = null
    private var outputStream: BufferedOutputStream? = null
    private var messageDigest: MessageDigest? = null
    private var watchdogJob: Job? = null

    // Callback to send packets back over Channel 0x07
    @Volatile
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
        if (payload.isEmpty() || payload.size > FileTransferConstants.WINDOW_SIZE_BYTES + 1) return
        if (incoming.trySend(payload).isFailure) {
            Log.w(TAG, "Incoming file transfer queue is full")
        }
    }

    private fun processIncomingPayload(payload: ByteArray) {

        when (payload[0]) {
            FileTransferConstants.HEADER_CONTROL_JSON -> {
                val jsonBytes = payload.copyOfRange(1, payload.size)
                handleControlPacket(jsonBytes)
            }
            FileTransferConstants.HEADER_RAW_BINARY -> {
                val binaryBytes = payload.copyOfRange(1, payload.size)
                handleBinaryWindow(binaryBytes)
            }
            else -> {
                if (isControlPacket(payload)) {
                    handleControlPacket(payload)
                } else {
                    handleBinaryWindow(payload)
                }
            }
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
        try {
            val expectedWindows = if (payload.fileSize == 0L) 1 else
                (payload.fileSize - 1) / FileTransferConstants.WINDOW_SIZE_BYTES + 1
            val validName = payload.fileName.isNotBlank() && payload.fileName !in setOf(".", "..") &&
                payload.fileName.none { it == '/' || it == '\\' || it.isISOControl() }
            if (activeTransferId == payload.transferId &&
                activeFileName == payload.fileName && activeFileSize == payload.fileSize &&
                expectedSha256 == payload.sha256 && totalWindows == payload.totalWindows &&
                activeFileType == payload.fileType && payload.resumeSupported) {
                resetWatchdog()
                sendStartAck(payload.transferId)
                return
            }
            val rejection = when {
                activeTransferId != null -> "Another transfer is in progress"
                payload.transferId.isBlank() || !validName || payload.fileSize < 0 ||
                    payload.totalWindows.toLong() != expectedWindows ||
                    !payload.sha256.matches(Regex("[0-9a-fA-F]{64}")) -> "Invalid transfer metadata"
                else -> null
            }
            if (rejection != null) {
                val ack = FileStartAckPayload(transferId = payload.transferId, status = "REJECTED", message = rejection)
                sendControlPayload(ack.toJson().toByteArray(Charsets.UTF_8))
                return
            }

            val transfersDir = context.getExternalFilesDir("transfers") ?: File(context.filesDir, "transfers")
            if (!transfersDir.exists()) {
                transfersDir.mkdirs()
            }

            val stagingDir = File(transfersDir, ".incoming")
            check(stagingDir.isDirectory || stagingDir.mkdirs()) { "Cannot create transfer staging directory" }
            check(payload.fileSize <= stagingDir.usableSpace) { "Not enough space for this transfer" }
            destinationFile = File(transfersDir, payload.fileName)

            activeTransferId = payload.transferId
            activeFileName = payload.fileName
            activeFileSize = payload.fileSize
            activeFileType = payload.fileType
            expectedSha256 = payload.sha256
            totalWindows = payload.totalWindows
            currentWindowIndex = 0
            bytesReceived = 0L
            transferStartTimeMs = SystemClock.elapsedRealtime()
            val temporaryFile = File.createTempFile("transfer-", ".part", stagingDir)
            activeFile = temporaryFile

            outputStream = BufferedOutputStream(FileOutputStream(temporaryFile), FileTransferConstants.WINDOW_SIZE_BYTES)
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

            // 1. Send explicit FILE_START_ACK so iOS knows the stream & digest are open before streaming Window 0
            sendStartAck(payload.transferId)
            if (!payload.resumeSupported) {
                val initAck = FileWindowAckPayload(
                    transferId = payload.transferId,
                    windowIndex = -1,
                    bytesReceived = 0L,
                    progress = 0.0
                )
                sendControlPayload(initAck.toJson().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file transfer: ${e.message}", e)
            _transferProgress.value = _transferProgress.value.copy(
                status = TransferStatus.FAILED,
                errorMessage = e.message
            )
            cleanupSession()
        }
    }

    private fun sendStartAck(transferId: String) {
        val ack = JSONObject()
            .put("cmd", FileTransferConstants.CMD_FILE_START_ACK)
            .put("transferId", transferId)
            .put("status", "READY")
            .put("nextWindow", currentWindowIndex)
            .put("bytesReceived", bytesReceived)
        sendControlPayload(ack.toString().toByteArray(Charsets.UTF_8))
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

        try {
            val expectedSize = minOf(
                activeFileSize - bytesReceived,
                FileTransferConstants.WINDOW_SIZE_BYTES.toLong()
            )
            check(currentWindowIndex < totalWindows && windowBytes.size.toLong() == expectedSize) {
                "Invalid file transfer window length"
            }
            out.write(windowBytes)
            digest.update(windowBytes)
            bytesReceived += windowBytes.size

            val now = SystemClock.elapsedRealtime()
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

    private fun onFileEnd(transferId: String) {
        if (activeTransferId != transferId) {
            Log.w(TAG, "Mismatch transferId on FILE_END: expected $activeTransferId, got $transferId")
            return
        }

        try {
            _transferProgress.value = _transferProgress.value.copy(status = TransferStatus.VERIFYING)

            outputStream?.flush()
            outputStream?.close()
            outputStream = null

            val digest = messageDigest ?: throw IllegalStateException("Digest is null")
            val computedHash = bytesToHex(digest.digest())
            val expected = expectedSha256 ?: ""

            val verified = bytesReceived == activeFileSize &&
                (currentWindowIndex == totalWindows || activeFileSize == 0L) &&
                computedHash.equals(expected, ignoreCase = true)
            val status = if (verified) FileTransferConstants.STATUS_VERIFIED else FileTransferConstants.STATUS_HASH_MISMATCH
            val savedFile = destinationFile
            val savedPath = savedFile?.absolutePath

            Log.i(TAG, "File transfer end: computed sha256=$computedHash, expected=$expected -> verification=$status")

            if (!verified) {
                _transferProgress.value = _transferProgress.value.copy(
                    status = TransferStatus.FAILED,
                    errorMessage = "SHA-256 hash mismatch! Computed: $computedHash, expected: $expected"
                )
            } else {
                val staged = checkNotNull(activeFile)
                val destination = checkNotNull(savedFile)
                Os.rename(staged.absolutePath, destination.absolutePath)
                activeFile = null
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

            cleanupSession()
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing file transfer: ${e.message}", e)
            _transferProgress.value = _transferProgress.value.copy(
                status = TransferStatus.FAILED,
                errorMessage = e.message
            )
            cleanupSession()
        }
    }

    private fun onFileCancel(transferId: String) {
        if (activeTransferId == transferId) {
            Log.i(TAG, "Transfer $transferId cancelled by peer")
            _transferProgress.value = _transferProgress.value.copy(
                status = TransferStatus.CANCELLED,
                errorMessage = "Transfer cancelled"
            )
            cleanupSession()
        }
    }

    fun cancelActiveTransfer() {
        val id = _transferProgress.value.transferId
        scope.launch {
            if (activeTransferId != id) return@launch
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
        val framed = ByteArray(1 + data.size)
        framed[0] = FileTransferConstants.HEADER_CONTROL_JSON
        System.arraycopy(data, 0, framed, 1, data.size)
        outboundPacketSender?.invoke(framed)
    }

    private fun cleanupSession() {
        watchdogJob?.cancel()
        watchdogJob = null

        try {
            outputStream?.close()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Failed to close incoming file", e)
        }
        outputStream = null
        messageDigest = null

        activeFile?.let { if (!it.delete() && it.exists()) Log.w(TAG, "Failed to delete staged file") }
        destinationFile = null

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
