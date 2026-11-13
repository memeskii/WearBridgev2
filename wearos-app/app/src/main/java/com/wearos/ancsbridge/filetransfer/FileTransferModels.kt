package com.wearos.ancsbridge.filetransfer

import org.json.JSONObject

enum class TransferStatus {
    IDLE,
    RECEIVING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class FileTransferProgress(
    val transferId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val bytesTransferred: Long = 0L,
    val progress: Float = 0f,
    val speedKBps: Double = 0.0,
    val status: TransferStatus = TransferStatus.IDLE,
    val errorMessage: String? = null,
    val savedPath: String? = null
)

object FileTransferConstants {
    const val HEADER_CONTROL_JSON: Byte = 0x01
    const val HEADER_RAW_BINARY: Byte = 0x02

    const val CMD_FILE_START = "FILE_START"
    const val CMD_FILE_START_ACK = "FILE_START_ACK"
    const val CMD_FILE_WINDOW_ACK = "FILE_WINDOW_ACK"
    const val CMD_FILE_END = "FILE_END"
    const val CMD_FILE_COMPLETE = "FILE_COMPLETE"
    const val CMD_FILE_CANCEL = "FILE_CANCEL"
    const val CMD_OTA_UPDATE_URL = "OTA_UPDATE_URL"

    const val STATUS_VERIFIED = "VERIFIED"
    const val STATUS_HASH_MISMATCH = "HASH_MISMATCH"

    const val FILE_TYPE_WATCH_FACE = "WATCH_FACE"
    const val FILE_TYPE_GENERAL = "GENERAL_FILE"
    const val FILE_TYPE_WATCH_APP_OTA = "WATCH_APP_OTA"

    const val WINDOW_SIZE_BYTES = 32 * 1024 // 32KB
}

data class FileStartAckPayload(
    val cmd: String = FileTransferConstants.CMD_FILE_START_ACK,
    val transferId: String,
    val status: String = "READY",
    val message: String = "Stream opened successfully"
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("cmd", cmd)
            put("transferId", transferId)
            put("status", status)
            put("message", message)
        }.toString()
    }
}

data class FileStartPayload(
    val cmd: String = FileTransferConstants.CMD_FILE_START,
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val fileType: String = FileTransferConstants.FILE_TYPE_WATCH_FACE,
    val sha256: String,
    val totalWindows: Int,
    val resumeSupported: Boolean = false
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("cmd", cmd)
            put("transferId", transferId)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("fileType", fileType)
            put("sha256", sha256)
            put("totalWindows", totalWindows)
            if (resumeSupported) put("resumeSupported", true)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FileStartPayload? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("cmd") != FileTransferConstants.CMD_FILE_START) return null
                FileStartPayload(
                    cmd = FileTransferConstants.CMD_FILE_START,
                    transferId = obj.getString("transferId"),
                    fileName = obj.getString("fileName"),
                    fileSize = obj.getLong("fileSize"),
                    fileType = obj.optString("fileType", FileTransferConstants.FILE_TYPE_WATCH_FACE),
                    sha256 = obj.getString("sha256"),
                    totalWindows = obj.optInt("totalWindows", 1),
                    resumeSupported = obj.optBoolean("resumeSupported")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class FileWindowAckPayload(
    val cmd: String = FileTransferConstants.CMD_FILE_WINDOW_ACK,
    val transferId: String,
    val windowIndex: Int,
    val bytesReceived: Long,
    val progress: Double
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("cmd", cmd)
            put("transferId", transferId)
            put("windowIndex", windowIndex)
            put("bytesReceived", bytesReceived)
            put("progress", progress)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FileWindowAckPayload? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("cmd") != FileTransferConstants.CMD_FILE_WINDOW_ACK) return null
                FileWindowAckPayload(
                    cmd = FileTransferConstants.CMD_FILE_WINDOW_ACK,
                    transferId = obj.getString("transferId"),
                    windowIndex = obj.getInt("windowIndex"),
                    bytesReceived = obj.getLong("bytesReceived"),
                    progress = obj.getDouble("progress")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class FileCompletePayload(
    val cmd: String = FileTransferConstants.CMD_FILE_COMPLETE,
    val transferId: String,
    val status: String, // "VERIFIED" | "HASH_MISMATCH"
    val savedPath: String?
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("cmd", cmd)
            put("transferId", transferId)
            put("status", status)
            put("savedPath", savedPath ?: "")
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FileCompletePayload? {
            return try {
                val obj = JSONObject(jsonStr)
                if (obj.optString("cmd") != FileTransferConstants.CMD_FILE_COMPLETE) return null
                val path = if (obj.has("savedPath") && !obj.isNull("savedPath")) obj.getString("savedPath") else null
                FileCompletePayload(
                    cmd = FileTransferConstants.CMD_FILE_COMPLETE,
                    transferId = obj.getString("transferId"),
                    status = obj.getString("status"),
                    savedPath = path
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
