package io.agents.arya.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.arya.R
import io.agents.arya.agent.llm.LocalModelManager
import io.agents.arya.agent.llm.ModelDownloadHub
import io.agents.arya.agent.llm.ModelReadiness
import io.agents.arya.ui.theme.LocalAryaPalette

@Composable
fun ModelStudioSheet(
    catalog: List<LocalModelManager.CatalogEntry>,
    readiness: ModelReadiness,
    ramGb: Int,
    onDownload: (LocalModelManager.ModelInfo) -> Unit,
    onActivate: (LocalModelManager.CatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAryaPalette.current
    val jobs by ModelDownloadHub.jobs.collectAsState()

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(palette.hairline),
        )
        Text(stringResource(R.string.models_title), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.models_ram_subtitle, ramGb),
            fontSize = 14.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (readiness) {
                is ModelReadiness.Local -> stringResource(R.string.model_active_local, readiness.label)
                is ModelReadiness.Cloud -> stringResource(R.string.model_active_cloud, readiness.label)
                is ModelReadiness.NeedsSetup -> readiness.reason
            },
            fontSize = 14.sp,
            color = if (readiness is ModelReadiness.NeedsSetup) palette.warning else palette.success,
        )
        Spacer(Modifier.height(18.dp))

        catalog.forEach { entry ->
            val job = jobs[entry.model.id]
            ModelCard(
                entry = entry,
                job = job,
                onDownload = { onDownload(entry.model) },
                onActivate = { onActivate(entry) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.models_background_downloads),
            fontSize = 12.sp,
            color = palette.textTertiary,
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ModelCard(
    entry: LocalModelManager.CatalogEntry,
    job: ModelDownloadHub.Job?,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
) {
    val palette = LocalAryaPalette.current
    val running = job?.phase == ModelDownloadHub.Phase.QUEUED || job?.phase == ModelDownloadHub.Phase.RUNNING
    val failed = job?.phase == ModelDownloadHub.Phase.FAILED
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.model.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    stringResource(
                        R.string.model_card_specs,
                        "%.1f".format(entry.model.sizeBytes / 1_000_000_000.0),
                        entry.model.minRamGb,
                    ) + if (!entry.isSupported) stringResource(R.string.model_card_below_floor) else "",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                )
            }
            val downloadAction = stringResource(R.string.model_action_download)
            val useAction = stringResource(R.string.model_action_use)
            val action = when {
                running -> stringResource(R.string.model_action_downloading)
                entry.isDownloaded && entry.isSupported -> useAction
                entry.isDownloaded -> stringResource(R.string.model_action_installed)
                entry.isSupported -> downloadAction
                else -> stringResource(R.string.model_action_too_large)
            }
            val clickable = (action == downloadAction || action == useAction) && !running
            Text(
                text = action,
                color = if (clickable) palette.accent else palette.textTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (clickable) palette.accentSoft else palette.canvas)
                    .clickable(enabled = clickable) {
                        if (action == downloadAction) onDownload() else onActivate()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        job?.takeIf { running }?.let { activeJob ->
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { activeJob.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = palette.accent,
                trackColor = palette.canvas,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${activeJob.percent}%", fontSize = 12.sp, color = palette.textSecondary)
                val mb = activeJob.bytesDownloaded / 1_000_000
                val total = if (activeJob.totalBytes > 0) activeJob.totalBytes / 1_000_000 else 0
                val speed = if (activeJob.bytesPerSecond > 0) {
                    stringResource(R.string.model_speed_suffix, activeJob.bytesPerSecond / 1000)
                } else {
                    ""
                }
                Text(stringResource(R.string.model_progress_size, mb, total, speed), fontSize = 12.sp, color = palette.textSecondary)
            }
        }
        job?.takeIf { failed }?.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 12.sp, color = palette.warning)
        }
    }
}
