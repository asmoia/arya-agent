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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Text("Models", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "On-device GGUF · this phone reports ${ramGb} GB RAM",
            fontSize = 14.sp,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (readiness) {
                is ModelReadiness.Local -> "Active · ${readiness.label}"
                is ModelReadiness.Cloud -> "Active · Cloud ${readiness.label}"
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
            "Downloads continue in the background. You can leave this screen.",
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
                    buildString {
                        append("%.1f GB".format(entry.model.sizeBytes / 1_000_000_000.0))
                        append(" · ")
                        append("${entry.model.minRamGb}+ GB RAM")
                        if (!entry.isSupported) append(" · this phone is below the floor")
                    },
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                )
            }
            val action = when {
                running -> "Downloading"
                entry.isDownloaded && entry.isSupported -> "Use"
                entry.isDownloaded -> "Installed"
                entry.isSupported -> "Download"
                else -> "Too large"
            }
            val clickable = (action == "Download" || action == "Use") && !running
            Text(
                text = action,
                color = if (clickable) palette.accent else palette.textTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (clickable) palette.accentSoft else palette.canvas)
                    .clickable(enabled = clickable) {
                        if (action == "Download") onDownload() else onActivate()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (running && job != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { job.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = palette.accent,
                trackColor = palette.canvas,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${job.percent}%", fontSize = 12.sp, color = palette.textSecondary)
                val mb = job.bytesDownloaded / 1_000_000
                val total = if (job.totalBytes > 0) job.totalBytes / 1_000_000 else 0
                val speed = if (job.bytesPerSecond > 0) " · ${job.bytesPerSecond / 1000} KB/s" else ""
                Text("$mb / $total MB$speed", fontSize = 12.sp, color = palette.textSecondary)
            }
        }
        if (failed && job?.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(job.error, fontSize = 12.sp, color = palette.warning)
        }
    }
}
