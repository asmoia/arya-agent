package io.agents.arya.ui.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.agents.arya.ui.chat.ChatMessage

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    reasoningText: String? = null,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        reverseLayout = false
    ) {
        if (reasoningText != null && reasoningText.isNotEmpty()) {
            item {
                ThinkBlock(
                    reasoningText = reasoningText,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }

        items(messages) { msg ->
            MessageBubble(message = msg)
        }
    }
}
