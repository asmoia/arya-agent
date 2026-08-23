package io.agents.arya.ui.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.agents.arya.PermissionState
import io.agents.arya.R

/**
 * First-run 3 permission steps bound to PermissionTruth (time-contract §UI).
 */
@Composable
fun OnboardingPermissions(
    state: PermissionState,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp).fillMaxWidth()) {
        Text(stringResource(R.string.guide_page_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.guide_page_subtitle), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        StepRow(
            title = stringResource(R.string.guide_title_accessibility),
            done = state.isAccessibilityEnabled,
            onClick = onOpenAccessibility,
        )
        StepRow(
            title = stringResource(R.string.guide_title_overlay),
            done = state.isOverlayEnabled,
            onClick = onOpenOverlay,
        )
        StepRow(
            title = stringResource(R.string.guide_title_notification),
            done = state.isNotificationListenerEnabled,
            onClick = onOpenNotifications,
        )
    }
}

@Composable
private fun StepRow(title: String, done: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        enabled = !done,
    ) {
        Text(if (done) "✓ $title" else title)
    }
}
