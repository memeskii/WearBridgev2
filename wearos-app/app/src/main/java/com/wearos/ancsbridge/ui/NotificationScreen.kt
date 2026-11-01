package com.wearos.ancsbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.ancs.AppIconRenderer
import com.wearos.ancsbridge.ancs.AppIconResolver
import com.wearos.ancsbridge.ble.BLEClientService
import com.wearos.ancsbridge.model.AncsNotification

/**
 * Wear OS Notification Screen displaying incoming live notification cards
 * with circular brand badge, brand-tinted pill header, canned quick replies,
 * ANCS Negative Action Dismiss, and visual confirmation state.
 */
@Composable
fun NotificationScreen(
    notifications: List<AncsNotification>,
    onSendReply: (notificationUid: Int, replyText: String) -> Boolean,
    onDismiss: (notificationUid: Long) -> Unit,
    onBack: (() -> Unit)? = null
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text(
                    text = if (notifications.isEmpty()) "Notifications" else "Notifications (${notifications.size})",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }

        if (notifications.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Notifications",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Alerts received from your iPhone will appear here.",
                        fontSize = 11.sp,
                        color = Color(0xFF9CA3AF),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            items(notifications.size) { index ->
                val notif = notifications[index]
                NotificationCard(
                    notificationUid = notif.uid.toInt(),
                    title = notif.title.ifEmpty { notif.appDisplayName ?: "Notification" },
                    message = notif.message.ifEmpty { notif.subtitle ?: "" },
                    appName = notif.appDisplayName ?: "",
                    bundleId = notif.appIdentifier,
                    onSendReply = onSendReply,
                    onDismiss = onDismiss
                )
            }
        }

        if (onBack != null) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text("Back", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Preview / single-notification overload for NotificationScreen.
 */
@Composable
fun NotificationScreen(
    bleClientService: BLEClientService?,
    notificationUid: Int = 1001,
    title: String = "Sarah Jenkins",
    message: String = "Are you available for a quick sync before the meeting?",
    appName: String = "Messages",
    bundleId: String = "com.apple.MobileSMS"
) {
    NotificationScreen(
        notifications = listOf(
            AncsNotification(
                uid = notificationUid.toLong(),
                appIdentifier = bundleId,
                appDisplayName = appName,
                title = title,
                subtitle = null,
                message = message,
                date = null,
                categoryId = 0,
                eventFlags = 0,
                positiveActionLabel = null,
                negativeActionLabel = null
            )
        ),
        onSendReply = { uid, replyText ->
            bleClientService?.sendQuickReply(uid, replyText) ?: true
        },
        onDismiss = {}
    )
}

/**
 * Incoming notification card with circular brand badge, brand-tinted header pill,
 * canned reply chips ("OK", "On my way", "Can't talk"), and ANCS Dismiss action.
 *
 * Automatically resolves iOS bundle ID to brand metadata (or smart monogram fallback).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationCard(
    notificationUid: Int,
    title: String,
    message: String,
    appName: String = "Messages",
    bundleId: String = "com.apple.MobileSMS",
    modifier: Modifier = Modifier,
    cannedReplies: List<String> = listOf("OK", "On my way", "Can't talk"),
    onSendReply: (notificationUid: Int, replyText: String) -> Boolean,
    onDismiss: (notificationUid: Long) -> Unit = {}
) {
    val context = LocalContext.current
    val brandInfo = remember(bundleId) { AppIconResolver.resolve(bundleId) }
    val brandBitmap = remember(bundleId) {
        AppIconRenderer.createLargeIconBitmap(context, bundleId, 48)
    }

    val primaryBrandColor = remember(brandInfo.primaryColorHex) {
        Color(brandInfo.primaryColorHex)
    }

    // Dark brand contrast fix: Brands like Uber and Threads (#1A1A1A) have luminance < 0.12
    val isDarkBrand = remember(brandInfo.primaryColorHex) {
        androidx.core.graphics.ColorUtils.calculateLuminance(brandInfo.primaryColorHex) < 0.12
    }
    val pillAccentColor = if (isDarkBrand) Color(0xFFE5E7EB) else primaryBrandColor
    val pillBackgroundColor = if (isDarkBrand) Color(0xFF2E3440) else primaryBrandColor.copy(alpha = 0.16f)
    val pillBorderColor = if (isDarkBrand) Color(0xFF4B5563) else primaryBrandColor.copy(alpha = 0.35f)
    val cardBorderColor = if (isDarkBrand) Color(0xFF374151) else primaryBrandColor.copy(alpha = 0.22f)

    val displayAppName = remember(appName, brandInfo.displayName) {
        if (appName.isNotBlank() && appName != bundleId) appName else brandInfo.displayName
    }

    // UI state for quick reply confirmation
    var sentReplyText by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }

    Card(
        onClick = { /* Card interaction */ },
        modifier = modifier
            .fillMaxWidth(0.96f)
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF181A1F),
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, cardBorderColor),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Brand-tinted Header Pill with Circular Brand Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        color = pillBackgroundColor,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = pillBorderColor,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Circular Brand Icon / Monogram Avatar
                Image(
                    bitmap = brandBitmap.asImageBitmap(),
                    contentDescription = displayAppName,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Resolved Display Name
                Text(
                    text = displayAppName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = pillAccentColor,
                    letterSpacing = 0.3.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Notification title / sender
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Notification body
            Text(
                text = message,
                fontSize = 13.sp,
                color = Color(0xFFD1D5DB),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            // Visual Confirmation or Canned Reply Chips
            if (sentReplyText != null) {
                // Visual confirmation state
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color(0xFF064E3B),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Confirmation",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Replied: \"$sentReplyText\"",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF34D399)
                        )
                    }
                }
            } else {
                // Canned Reply Chips
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Quick Reply",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF9CA3AF)
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        cannedReplies.forEach { reply ->
                            CompactButton(
                                onClick = {
                                    if (!isSending) {
                                        isSending = true
                                        val success = onSendReply(notificationUid, reply)
                                        if (success) {
                                            sentReplyText = reply
                                        }
                                        isSending = false
                                    }
                                },
                                enabled = !isSending,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E3440),
                                    contentColor = Color(0xFFE5E7EB)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = reply,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // ANCS Dismiss Negative Action (clears on watch & iPhone)
                        CompactButton(
                            onClick = {
                                onDismiss(notificationUid.toLong())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3B1E1E),
                                contentColor = Color(0xFFFCA5A5)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Dismiss",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (isSending) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
