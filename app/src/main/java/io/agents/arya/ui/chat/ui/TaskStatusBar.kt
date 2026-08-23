package io.agents.arya.ui.chat.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.agents.arya.R
import io.agents.arya.TaskState
import io.agents.arya.debug.BatteryEstimate

@Composable
fun TaskStatusBar(
    taskState: TaskState,
    onRequestStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (taskState is TaskState.Idle || taskState is TaskState.Cancelled || taskState is TaskState.Failed) {
        return
    }

    val statusText = when (taskState) {
        is TaskState.Routing -> stringResource(R.string.task_status_routing)
        is TaskState.Executing -> stringResource(R.string.task_status_executing, taskState.stepDescription)
        is TaskState.ConfirmPending -> stringResource(R.string.task_status_confirm, taskState.actionDescription)
        is TaskState.Stopping -> stringResource(R.string.task_status_stopping)
        is TaskState.Finished -> {
            val elapsed = (System.currentTimeMillis() - taskState.startedAt).coerceAtLeast(0L)
            val mah = BatteryEstimate.format(BatteryEstimate.estimateMah(elapsed, true, 0))
            stringResource(R.string.task_status_done, taskState.resultSummary, mah)
        }
        else -> ""
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (taskState !is TaskState.Finished) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            if (taskState !is TaskState.Finished) {
                IconButton(onClick = onRequestStop, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.chat_cd_stop_task),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
