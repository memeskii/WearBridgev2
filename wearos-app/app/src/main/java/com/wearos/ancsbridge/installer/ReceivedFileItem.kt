package com.wearos.ancsbridge.installer

data class ReceivedFileItem(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val isVerified: Boolean = true,
    val installStatus: String = STATUS_READY, // READY, INSTALLING, INSTALLED, FAILED
    val sha256: String = "",
    val errorMessage: String? = null
) {
    val isApk: Boolean
        get() = fileName.endsWith(".apk", ignoreCase = true)

    val isWatchFaceFormat: Boolean
        get() = fileName.endsWith(".wff", ignoreCase = true) || fileName.endsWith(".watch", ignoreCase = true) || isApk

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            return if (kb > 1024.0) {
                String.format("%.2f MB", kb / 1024.0)
            } else {
                String.format("%.1f KB", kb)
            }
        }

    companion object {
        const val STATUS_READY = "READY"
        const val STATUS_INSTALLING = "INSTALLING"
        const val STATUS_INSTALLED = "INSTALLED"
        const val STATUS_FAILED = "FAILED"
    }
}
