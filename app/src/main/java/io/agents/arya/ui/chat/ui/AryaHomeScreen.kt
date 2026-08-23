package io.agents.arya.ui.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.arya.TaskState
import io.agents.arya.agent.llm.ModelDownloadHub
import io.agents.arya.agent.llm.ModelReadiness
import io.agents.arya.ui.chat.ChatUiState
import io.agents.arya.ui.theme.LocalAryaPalette

@Composable
fun AryaHomeScreen(
    chatUiState: ChatUiState,
    taskState: TaskState,
    readiness: ModelReadiness,
    listening: Boolean,
    voicePartial: String,
    voiceError: String?,
    onSendText: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onToggleVoice: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onStopStreaming: () -> Unit,
    onRequestStopTask: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapabilities: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAryaPalette.current
    val snackbar = remember { SnackbarHostState() }
    val jobs by ModelDownloadHub.jobs.collectAsState()
    val activeDownload = jobs.values.firstOrNull {
        it.phase == ModelDownloadHub.Phase.QUEUED || it.phase == ModelDownloadHub.Phase.RUNNING
    }

    LaunchedEffect(chatUiState.errorMessage) {
        val err = chatUiState.errorMessage
        if (!err.isNullOrBlank()) snackbar.showSnackbar(err)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(palette.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Arya", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                    Text("On-device assistant", fontSize = 14.sp, color = palette.textSecondary)
                }
                IconButton(onClick = onOpenCapabilities) {
                    Icon(Icons.Outlined.Shield, contentDescription = "Capabilities", tint = palette.text)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = palette.text)
                }
            }

            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.surface)
                    .clickable(onClick = onOpenModels)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (readiness) {
                                is ModelReadiness.Local -> palette.success
                                is ModelReadiness.Cloud -> palette.accent
                                is ModelReadiness.NeedsSetup -> palette.warning
                            },
                        ),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (readiness) {
                            is ModelReadiness.Local -> readiness.label
                            is ModelReadiness.Cloud -> "Cloud · ${readiness.label}"
                            is ModelReadiness.NeedsSetup -> "Choose a model"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.text,
                    )
                    Text(
                        if (activeDownload != null) {
                            "Downloading ${activeDownload.displayName} · ${activeDownload.percent}%"
                        } else {
                            when (readiness) {
                                is ModelReadiness.Local -> "On this phone · tap to change"
                                is ModelReadiness.Cloud -> "Uses your API key · tap to change"
                                is ModelReadiness.NeedsSetup -> "Download Qwen3 to chat offline"
                            }
                        },
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                    )
                }
                Text("›", fontSize = 22.sp, color = palette.textTertiary)
            }

            if (activeDownload != null) {
                LinearProgressIndicator(
                    progress = { activeDownload.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .height(4.dp)
                        .clip(CircleShape),
                    color = palette.accent,
                    trackColor = palette.surface,
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }

            if (!chatUiState.statusLine.isNullOrBlank()) {
                Text(
                    chatUiState.statusLine ?: "",
                    color = palette.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            val loadPct = chatUiState.loadPercent
            if (loadPct != null && chatUiState.isStreaming && chatUiState.messages.none { it.role == io.agents.arya.ui.chat.ChatMessage.Role.ASSISTANT && it.content.isNotBlank() }) {
                LinearProgressIndicator(
                    progress = { (loadPct.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .height(4.dp)
                        .clip(CircleShape),
                    color = palette.accent,
                    trackColor = palette.surface,
                )
            }

            TaskStatusBar(taskState = taskState, onRequestStop = onRequestStopTask)

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (chatUiState.messages.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AryaVoiceOrb(
                            listening = listening,
                            hero = true,
                            onTap = onToggleVoice,
                            onHoldStart = onHoldStart,
                            onHoldEnd = onHoldEnd,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            if (listening) (voicePartial.ifBlank { "Listening…" })
                            else "Press and hold the orb to talk. Tap to keep listening.\nKeyboard is on the left.",
                            color = palette.textSecondary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp,
                        )
                        if (!voiceError.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(voiceError, color = palette.warning, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    MessageList(
                        messages = chatUiState.messages,
                        reasoningText = chatUiState.streamingReasoning,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AryaDock(
                draft = chatUiState.draftText,
                listening = listening,
                listeningHint = when {
                    !voiceError.isNullOrBlank() -> voiceError
                    listening -> voicePartial.ifBlank { "Listening… hold or tap again to stop" }
                    else -> ""
                },
                isStreaming = chatUiState.isStreaming,
                onDraftChange = onDraftChange,
                onSend = onSendText,
                onToggleVoice = onToggleVoice,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onStopStreaming = onStopStreaming,
            )
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
