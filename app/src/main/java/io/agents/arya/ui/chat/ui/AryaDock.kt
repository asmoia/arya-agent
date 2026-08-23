package io.agents.arya.ui.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.arya.ui.theme.LocalAryaPalette

@Composable
fun AryaDock(
    draft: String,
    listening: Boolean,
    listeningHint: String,
    isStreaming: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onToggleVoice: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit = onHoldEnd,
    onStopStreaming: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAryaPalette.current
    var keyboardOpen by remember { mutableStateOf(draft.isNotBlank()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(draft) {
        if (draft.isNotBlank()) keyboardOpen = true
    }
    LaunchedEffect(keyboardOpen) {
        if (keyboardOpen) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (listening && listeningHint.isNotBlank()) {
            Text(
                text = listeningHint,
                color = palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = keyboardOpen,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    textStyle = TextStyle(color = palette.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(palette.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank()) onSend(draft)
                        },
                    ),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text("Message Arya", color = palette.textTertiary, fontSize = 17.sp)
                        }
                        inner()
                    },
                )
                IconButton(
                    onClick = {
                        if (isStreaming) onStopStreaming()
                        else if (draft.isNotBlank()) onSend(draft)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isStreaming) "Stop" else "Send",
                        tint = if (draft.isNotBlank() || isStreaming) palette.accent else palette.textTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { keyboardOpen = !keyboardOpen },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Keyboard",
                    tint = if (keyboardOpen) palette.accent else palette.text,
                )
            }

            AryaVoiceOrb(
                listening = listening,
                hero = false,
                onTap = onToggleVoice,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
            )

            Spacer(Modifier.width(48.dp))
        }
    }
}
