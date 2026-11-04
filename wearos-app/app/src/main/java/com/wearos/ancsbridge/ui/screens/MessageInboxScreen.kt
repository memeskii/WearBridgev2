package com.wearos.ancsbridge.ui.screens

import android.app.Activity
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
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
import androidx.compose.ui.res.painterResource
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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.ancs.AppBrandRegistry
import com.wearos.ancsbridge.contacts.AppDatabase
import com.wearos.ancsbridge.messages.ConversationSummary
import com.wearos.ancsbridge.messages.MessageEntity
import com.wearos.ancsbridge.messages.MessageReplyManager
import com.wearos.ancsbridge.messages.MessageSyncManager

@Composable
fun MessageInboxScreen() {
    val context = LocalContext.current
    val syncManager = remember { MessageSyncManager.getInstance(context) }
    val replyManager = remember { MessageReplyManager.getInstance(context) }

    val conversationSummaries by syncManager.getConversationThreads().collectAsState(initial = emptyList())
    var selectedThread by remember { mutableStateOf<ConversationSummary?>(null) }

    if (selectedThread != null) {
        ThreadDetailScreen(
            thread = selectedThread!!,
            onBack = { selectedThread = null },
            onSendReply = { text ->
                val current = selectedThread!!
                replyManager.sendReply(
                    threadKey = current.threadKey,
                    appBundleId = current.appBundleId,
                    recipient = current.senderName,
                    senderName = current.senderName,
                    replyText = text,
                    ancsUid = 0L
                )
            }
        )
    } else {
        ThreadListScreen(
            threads = conversationSummaries,
            onSelectThread = { thread ->
                selectedThread = thread
            }
        )
    }
}

@Composable
private fun ThreadListScreen(
    threads: List<ConversationSummary>,
    onSelectThread: (ConversationSummary) -> Unit
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
                    text = "MESSAGES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
            }
        }

        if (threads.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Inbox Empty",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Incoming WhatsApp, SMS, and Telegram messages will appear here.",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(threads, key = { it.threadKey }) { thread ->
                ThreadRowCard(thread = thread, onClick = { onSelectThread(thread) })
            }
        }
    }
}

@Composable
private fun ThreadRowCard(
    thread: ConversationSummary,
    onClick: () -> Unit
) {
    val brand = AppBrandRegistry.resolve(thread.appBundleId)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 3.dp),
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
            // Circular Brand Vector Badge
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(Color(brand.primaryColor), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = brand.iconRes),
                    contentDescription = brand.displayName,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = thread.senderName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (thread.unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF00E676), CircleShape)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${thread.unreadCount}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = thread.latestMessageBody,
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadDetailScreen(
    thread: ConversationSummary,
    onBack: () -> Unit,
    onSendReply: (String) -> Unit
) {
    val context = LocalContext.current
    val messageDao = remember { AppDatabase.getInstance(context).messageDao() }
    val messages by messageDao.getMessagesForThread(thread.threadKey).collectAsState(initial = emptyList())
    val brand = AppBrandRegistry.resolve(thread.appBundleId)

    val quickReplies = listOf("Yes", "On my way", "OK", "Can't talk now", "Call you later")

    // Launcher for Android RemoteInput voice / keyboard prompt
    val remoteInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val bundle = RemoteInput.getResultsFromIntent(result.data)
            val replyText = bundle?.getCharSequence("quick_reply_input")?.toString()
            if (!replyText.isNullOrBlank()) {
                onSendReply(replyText)
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back & Brand Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.size(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3038))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(Color(brand.primaryColor), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = brand.iconRes),
                        contentDescription = brand.displayName,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column {
                    Text(
                        text = thread.senderName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = brand.displayName,
                        fontSize = 8.sp,
                        color = Color(brand.accentColor)
                    )
                }
            }
        }

        // Messages in thread
        items(messages, key = { it.id }) { msg ->
            MessageBubbleRow(msg = msg)
        }

        // Quick Reply Chips Header
        item {
            Text(
                text = "QUICK REPLIES (0x06)",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        // Quick Reply Chips
        items(quickReplies) { replyText ->
            Button(
                onClick = { onSendReply(replyText) },
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .padding(vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = null,
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = replyText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }

        // Voice / Keyboard Custom Reply Button
        item {
            Button(
                onClick = {
                    val intent = Intent("android.intent.action.REMOTE_INPUT_ENTRY")
                    val remoteInput = RemoteInput.Builder("quick_reply_input")
                        .setLabel("Reply to ${thread.senderName}")
                        .build()
                    RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, Bundle())
                    try {
                        remoteInputLauncher.launch(intent)
                    } catch (_: Exception) {
                        onSendReply("OK")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004D40))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Voice Reply",
                        tint = Color(0xFF80CBC4),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Voice / Text Reply",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubbleRow(msg: MessageEntity) {
    val isOut = msg.isOutgoing
    val alignment = if (isOut) Alignment.End else Alignment.Start
    val bg = if (isOut) Color(0xFF0D47A1) else Color(0xFF263238)

    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bg, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = msg.body,
                fontSize = 10.sp,
                color = Color.White
            )
        }

        if (isOut) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 1.dp)
            ) {
                when (msg.deliveryStatus) {
                    "SENDING" -> {
                        Icon(
                            imageVector = Icons.Rounded.HourglassTop,
                            contentDescription = "Sending",
                            tint = Color.Gray,
                            modifier = Modifier.size(9.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "Sending...", fontSize = 8.sp, color = Color.Gray)
                    }
                    "SENT" -> {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Sent",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(9.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "Delivered", fontSize = 8.sp, color = Color(0xFF00E676))
                    }
                    "FAILED" -> {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = "Failed",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(9.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "Failed", fontSize = 8.sp, color = Color(0xFFFF5252))
                    }
                }
            }
        }
    }
}
