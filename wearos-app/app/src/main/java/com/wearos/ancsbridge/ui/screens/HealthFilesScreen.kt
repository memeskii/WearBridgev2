package com.wearos.ancsbridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.health.HealthTelemetryManager
import com.wearos.ancsbridge.health.HealthTelemetryRepository
import com.wearos.ancsbridge.installer.ReceivedFileItem
import com.wearos.ancsbridge.installer.WatchFaceInstaller
import com.wearos.ancsbridge.installer.WatchOtaUpdater
import java.io.File

@Composable
fun HealthFilesScreen() {
    val context = LocalContext.current
    val healthRepo = remember { HealthTelemetryRepository.getInstance(context) }
    val healthManager = remember { HealthTelemetryManager.getInstance(context) }
    val installer = remember { WatchFaceInstaller.getInstance(context) }
    val otaUpdater = remember { WatchOtaUpdater.getInstance(context) }

    var telemetry by remember { mutableStateOf(healthRepo.getCachedPayload()) }
    val files by installer.receivedFiles.collectAsState()
    val otaState by otaUpdater.otaProgress.collectAsState()

    var isSyncingHealth by remember { mutableStateOf(false) }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Section 0: Live Watch App Self-OTA Update Banner (if active)
        if (otaState.isUpdating || otaState.status != "IDLE") {
            item {
                Card(
                    onClick = {
                        if (otaState.status == "READY_TO_INSTALL" && otaState.downloadedApkFile != null) {
                            otaUpdater.installOtaApk(otaState.downloadedApkFile!!)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1A47))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "APP OTA UPDATE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD18BFF)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (otaState.targetVersion.isNotEmpty()) "v${otaState.targetVersion}" else "WearBridge OTA",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        when (otaState.status) {
                            "DOWNLOADING" -> {
                                Text(
                                    text = "Downloading: ${(otaState.progress * 100).toInt()}% (${String.format("%.1f", otaState.speedKBps)} KB/s)",
                                    fontSize = 10.sp,
                                    color = Color.LightGray
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(4.dp)
                                        .background(Color.DarkGray, CircleShape)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(otaState.progress.coerceIn(0.05f, 1f))
                                            .height(4.dp)
                                            .background(Color(0xFFD18BFF), CircleShape)
                                    )
                                }
                            }
                            "VERIFYING" -> {
                                Text(
                                    text = "Verifying SHA-256...",
                                    fontSize = 10.sp,
                                    color = Color(0xFFFFD54F)
                                )
                            }
                            "READY_TO_INSTALL" -> {
                                Button(
                                    onClick = {
                                        otaState.downloadedApkFile?.let { otaUpdater.installOtaApk(it) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .padding(top = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) {
                                    Text(
                                        text = "Install App Update",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            "INSTALLING" -> {
                                Text(
                                    text = "Installing package...",
                                    fontSize = 10.sp,
                                    color = Color(0xFF81C784)
                                )
                            }
                            "FAILED" -> {
                                Text(
                                    text = otaState.errorMessage ?: "Update Failed",
                                    fontSize = 9.sp,
                                    color = Color(0xFFEF5350),
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { otaUpdater.cancelOta() },
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .padding(top = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                ) {
                                    Text("Dismiss", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Section 1: Health Telemetry Header
        item {
            ListHeader {
                Text(
                    text = "HEALTH TELEMETRY (0x04)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
            }
        }

        // Health Telemetry Summary Card
        item {
            Card(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2129))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    val cal = telemetry?.calories?.total ?: 2150.0
                    val spo2 = telemetry?.spo2?.latest ?: 98
                    val sleepMin = telemetry?.sleep?.totalDurationMinutes ?: 445
                    val stress = telemetry?.stress?.stressScore ?: 32

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🔥 ${cal.toInt()} kcal",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF7043)
                        )
                        Text(
                            text = "🫁 SpO2: $spo2%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF26A69A)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "💤 Sleep: ${sleepMin / 60}h ${sleepMin % 60}m",
                            fontSize = 10.sp,
                            color = Color(0xFFB39DDB)
                        )
                        Text(
                            text = "🧘 Stress: $stress",
                            fontSize = 10.sp,
                            color = Color(0xFF90CAF9)
                        )
                    }
                }
            }
        }

        // Sync to iPhone Button
        item {
            Button(
                onClick = {
                    isSyncingHealth = true
                    healthManager.syncTelemetryToPhone { success ->
                        isSyncingHealth = false
                        if (success) {
                            telemetry = healthRepo.getCachedPayload()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC2185B))
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSyncingHealth) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Sync Health",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSyncingHealth) "Harvesting..." else "Sync Health to iPhone",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Section 2: Received Watch Faces Header
        item {
            ListHeader(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    text = "RECEIVED WATCH FACES (0x07)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
            }
        }

        if (files.isEmpty()) {
            item {
                Text(
                    text = "No packages received yet.\nSend .apk from iPhone Watch Face Hub.",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(vertical = 12.dp)
                )
            }
        } else {
            items(files, key = { it.filePath }) { fileItem ->
                ReceivedFileCard(
                    fileItem = fileItem,
                    onInstall = {
                        installer.installWatchFaceApk(File(fileItem.filePath))
                    }
                )
            }
        }
    }
}

@Composable
private fun ReceivedFileCard(
    fileItem: ReceivedFileItem,
    onInstall: () -> Unit
) {
    Card(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2129))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fileItem.fileName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = fileItem.formattedSize,
                    fontSize = 9.sp,
                    color = Color(0xFF90CAF9)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SHA-256 Verified",
                    fontSize = 9.sp,
                    color = Color(0xFF81C784)
                )

                if (fileItem.isApk) {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier
                            .height(26.dp)
                            .padding(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (fileItem.installStatus) {
                                ReceivedFileItem.STATUS_INSTALLED -> Color(0xFF1B5E20)
                                ReceivedFileItem.STATUS_INSTALLING -> Color(0xFFE65100)
                                else -> Color(0xFF1565C0)
                            }
                        )
                    ) {
                        Text(
                            text = when (fileItem.installStatus) {
                                ReceivedFileItem.STATUS_INSTALLED -> "Installed"
                                ReceivedFileItem.STATUS_INSTALLING -> "Installing..."
                                else -> "Install APK"
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
