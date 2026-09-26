package com.supervideo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.supervideo.core.job.JobManifest
import com.supervideo.core.job.JobStatus
import com.supervideo.core.pipeline.JobProgress
import com.supervideo.core.util.Durations
import com.supervideo.ui.AppGraph
import com.supervideo.ui.form.Section
import com.supervideo.ui.theme.spacing
import kotlin.math.ceil

@Composable
fun JobsScreen(graph: AppGraph) {
    val jobs by graph.jobRepository.observe().collectAsState()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (jobs.isEmpty()) {
            Text("No jobs yet", modifier = Modifier.padding(MaterialTheme.spacing.level7))
        }
        LazyColumn(
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(MaterialTheme.spacing.level5),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level4),
        ) {
            items(jobs, key = { it.jobId }) { job -> JobRow(graph, job) }
        }
    }
}

@Composable
private fun JobRow(graph: AppGraph, job: JobManifest) {
    val flow = remember(job.jobId, job.status) { graph.jobRunner.progress(job.jobId) }
    val live: State<JobProgress?>? = flow?.collectAsState()
    val progress = live?.value?.takeIf { job.status.isActive || it.status.isActive }

    val framesDone = progress?.framesDone ?: job.framesDone
    val total = maxOf(progress?.estimatedFrames ?: job.estimatedFrames, framesDone, 1)
    val status = progress?.status ?: job.status
    val totalSegments = ceil(total.toDouble() / job.settings.segmentFrames).toInt().coerceAtLeast(1)

    Section(job.sourceName) {
        Text(
            "${job.settings.model.displayName} · ${job.outputWidth}×${job.outputHeight} · ${job.settings.codec.displayName}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level3)) {
            AssistChip(onClick = {}, label = { Text(statusLabel(status, progress)) })
            progress?.activeBackend?.let { Text(it.displayName, style = MaterialTheme.typography.labelMedium) }
        }
        if (status != JobStatus.DONE) {
            LinearProgressIndicator(
                progress = { if (status == JobStatus.MUXING) 1f else (framesDone.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            buildString {
                append("$framesDone / $total frames")
                if (status == JobStatus.RUNNING || status == JobStatus.PAUSED) {
                    append(" · segment ${progress?.currentSegment ?: (job.segments.size + 1)}/$totalSegments")
                    progress?.takeIf { it.msPerFrame > 0 }?.let {
                        append(" · %.0f ms/frame".format(it.msPerFrame))
                        it.etaMillis?.let { eta -> append(" · ETA ${Durations.period(eta)}") }
                    }
                }
                if (status == JobStatus.DONE && job.upscaleMillis > 0) append(" · upscaling took ${Durations.period(job.upscaleMillis)}")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        job.error?.takeIf { status == JobStatus.FAILED }?.let { ErrorText(it) }
        job.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
        job.outputUri?.takeIf { status == JobStatus.DONE }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.level2)) {
            when (status) {
                JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.PAUSED ->
                    TextButton(onClick = { graph.jobRunner.cancel(job.jobId) }) { Text("Cancel") }
                JobStatus.CANCELLED, JobStatus.FAILED ->
                    TextButton(onClick = { graph.jobRunner.resume(job.jobId) }) { Text("Resume") }
                JobStatus.DONE ->
                    TextButton(onClick = { job.outputUri?.let(graph.platform::openOutput) }) { Text("Open output") }
                JobStatus.MUXING -> {}
            }
            if (!status.isActive && status != JobStatus.QUEUED) {
                TextButton(onClick = { graph.jobRepository.delete(job.jobId) }) { Text("Delete") }
            }
        }
    }
}

private fun statusLabel(status: JobStatus, progress: JobProgress?): String = when (status) {
    JobStatus.QUEUED -> "Queued"
    JobStatus.RUNNING -> "Running"
    JobStatus.PAUSED -> "Paused: ${progress?.pausedReason?.label ?: "waiting"}"
    JobStatus.MUXING -> "Muxing"
    JobStatus.DONE -> "Done"
    JobStatus.FAILED -> "Failed"
    JobStatus.CANCELLED -> "Cancelled"
}
