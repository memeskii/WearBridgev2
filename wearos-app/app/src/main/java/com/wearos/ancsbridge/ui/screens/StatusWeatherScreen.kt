package com.wearos.ancsbridge.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@SuppressLint("MissingPermission")
@Composable
fun StatusWeatherScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val batteryLevel by viewModel.watchBatteryLevel.collectAsStateWithLifecycle()
    val isCharging by viewModel.isWatchCharging.collectAsStateWithLifecycle()
    val companionConnected by viewModel.companionConnected.collectAsStateWithLifecycle()
    val weatherData by viewModel.weatherData.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val phoneStatus = remember(context) {
        context.getSharedPreferences("phone_status", android.content.Context.MODE_PRIVATE)
    }
    val phoneBattery = remember(phoneStatus) { mutableIntStateOf(phoneStatus.getInt("battery", -1)) }
    val batteryUpdatedAt = remember(phoneStatus) {
        androidx.compose.runtime.mutableLongStateOf(phoneStatus.getLong("updatedAt", 0L))
    }
    DisposableEffect(phoneStatus) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "battery") phoneBattery.intValue = prefs.getInt("battery", -1)
            if (key == "updatedAt") batteryUpdatedAt.longValue = prefs.getLong("updatedAt", 0L)
        }
        phoneStatus.registerOnSharedPreferenceChangeListener(listener)
        onDispose { phoneStatus.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val isConnected = connectionState is ConnectionState.Connected
    val deviceName = when (val state = connectionState) {
        is ConnectionState.Connected -> state.deviceName ?: "iPhone Connected"
        is ConnectionState.Connecting -> state.deviceName ?: "Connecting..."
        is ConnectionState.Bonding -> state.deviceName ?: "Pairing..."
        else -> "iPhone Disconnected"
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Connection Status Card
        item {
            Card(
                onClick = {
                    if (!isConnected && !isScanning) {
                        viewModel.scanAndConnect()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E2129)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                if (isConnected) Color(0xFF1B5E20) else Color(0xFFE65100),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bluetooth,
                            contentDescription = "Bluetooth",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = deviceName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isConnected) "Notifications connected" else if (isScanning) "Searching..." else "Tap to Connect",
                            fontSize = 10.sp,
                            color = if (isConnected) Color(0xFF81C784) else Color(0xFFFFB74D)
                        )
                        Text(
                            text = if (companionConnected) "Companion sync active" else "Companion sync offline",
                            fontSize = 10.sp,
                            color = Color.LightGray
                        )
                        if (phoneBattery.intValue in 0..100) {
                            Text(
                                text = "iPhone ${phoneBattery.intValue}% · ${updatedTime(batteryUpdatedAt.longValue)}",
                                fontSize = 10.sp,
                                color = Color.LightGray
                            )
                        }
                    }

                    if (batteryLevel != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                                contentDescription = "Battery",
                                tint = if (isCharging) Color(0xFF81C784) else Color.LightGray,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "$batteryLevel%",
                                fontSize = 11.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 2. Weather Summary Card
        item {
            Card(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF152238)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = weatherData?.cityName ?: "Weather",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF90CAF9)
                            )
                            Text(
                                text = weatherData?.conditionDescription() ?: "Waiting for iPhone sync",
                                fontSize = 10.sp,
                                color = Color.LightGray
                            )
                        }

                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = weatherData?.let { String.format(Locale.getDefault(), "%.0f°", it.temp) }.orEmpty(),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Light,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = weatherData?.let { String.format(Locale.getDefault(), "H: %.0f° L: %.0f°", it.high, it.low) }.orEmpty(),
                            fontSize = 9.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = weatherData?.let { "Updated ${updatedTime(it.updatedAt * 1000L)}" }.orEmpty(),
                            fontSize = 9.sp,
                            color = Color(0xFF64B5F6)
                        )
                    }

                    // 12-hour hourly forecast strip
                    val hourly = weatherData?.hourly
                    if (!hourly.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val items = hourly.take(4)
                            for (item in items) {
                                val hourStr = SimpleDateFormat("ha", Locale.US).format(Date(item.time * 1000L)).lowercase()
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = hourStr, fontSize = 8.sp, color = Color.Gray)
                                    Icon(
                                        imageVector = if (item.code == 0) Icons.Rounded.WbSunny else Icons.Rounded.Cloud,
                                        contentDescription = null,
                                        tint = Color(0xFFFFD54F),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(text = "${item.temp.toInt()}°", fontSize = 9.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Find My iPhone Action Card
        item {
            Button(
                onClick = {
                    viewModel.ringiPhone()
                },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF283593)
                )
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = "Ring iPhone",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ring My iPhone",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Scanning indicator banner
        if (isScanning) {
            item {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = scanStatus.ifEmpty { "Scanning..." },
                        fontSize = 10.sp,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

internal fun updatedTime(timestamp: Long): String {
    if (timestamp <= 0L) return "time unknown"
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.SHORT
    ).format(Date(timestamp))
}
