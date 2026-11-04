package com.wearos.ancsbridge.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import com.wearos.ancsbridge.ui.screens.HealthFilesScreen
import com.wearos.ancsbridge.ui.screens.MediaPlayerScreen
import com.wearos.ancsbridge.ui.screens.MessageInboxScreen
import com.wearos.ancsbridge.ui.screens.StatusWeatherScreen
import com.wearos.ancsbridge.viewmodel.MainViewModel

/**
 * Main Wear OS Hub Launcher combining 4 primary screens in a smooth Horizontal Pager:
 * Screen 1: Status & Synced Weather
 * Screen 2: Native AMS + Channel 0x05 Media Player
 * Screen 3: Persistent Messages Inbox & Outbound Replies (0x06)
 * Screen 4: Health Telemetry (0x04) & Transferred Watch Faces / APK Installer (0x07)
 */
@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })

    AppScaffold {
        ScreenScaffold(
            timeText = { TimeText() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> StatusWeatherScreen(viewModel = viewModel)
                        1 -> MediaPlayerScreen()
                        2 -> MessageInboxScreen()
                        3 -> HealthFilesScreen()
                    }
                }

                // Page Indicator Dots
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(4) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 6.dp else 4.dp)
                                .background(
                                    color = if (isSelected) Color.White else Color(0xFF616161),
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
        }
    }
}
