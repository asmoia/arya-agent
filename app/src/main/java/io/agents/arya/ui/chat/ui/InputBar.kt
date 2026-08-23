package io.agents.arya.ui.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.agents.arya.R

private const val HOLD_COMMIT_MS = 300L

@Composable
fun InputBar(
    onSendText: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onVoiceCancel: () -> Unit = {},
    onToggleVoice: () -> Unit = {},
    isListening: Boolean = false,
    isStreaming: Boolean,
    onStopStreaming: () -> Unit,
    initialText: String = "",
    modifier: Modifier = Modifier,
    /** Kept so older call sites that only pass onStartVoiceInput still compile. */
    onStartVoiceInput: () -> Unit = onVoiceStart,
) {
    var text by remember { mutableStateOf(initialText) }
    var pressed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(initialText) {
        if (initialText.isNotBlank() || text.isNotEmpty()) {
            text = initialText
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val micTint = when {
                isListening -> MaterialTheme.colorScheme.error
                pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isListening -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                    .pointerInput(isListening) {
                        detectTapGestures(
                            onPress = {
                                val downAt = System.currentTimeMillis()
                                pressed = true
                                onVoiceStart()
                                val released = tryAwaitRelease()
                                val heldMs = System.currentTimeMillis() - downAt
                                pressed = false
                                if (!released || heldMs < HOLD_COMMIT_MS) {
                                    // Releasing before 300ms cancels instead of sending.
                                    onVoiceCancel()
                                    if (released) onToggleVoice()
                                } else {
                                    onVoiceStop()
                                }
                            },
                            onTap = {
                                // Tap = toggle listening mode (fallback if onPress was cancelled).
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.chat_cd_voice),
                    tint = micTint,
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                maxLines = 4,
                shape = CircleShape
            )

            if (isStreaming) {
                IconButton(
                    onClick = onStopStreaming,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.chat_cd_stop),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSendText(text)
                            text = ""
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = stringResource(R.string.chat_cd_send),
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}
