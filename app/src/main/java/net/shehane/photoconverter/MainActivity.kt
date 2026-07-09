package net.shehane.photoconverter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.shehane.photoconverter.core.AnalysisResult
import net.shehane.photoconverter.core.ConversionResult
import net.shehane.photoconverter.core.FileIssue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FP16 HDR surface: HWUI applies Ultra HDR gainmaps and renders wide-gamut
        // (e.g. Display P3) bitmaps correctly on capable displays
        window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var gallery by remember { mutableStateOf<ConversionResult?>(null) }
                    val current = gallery
                    if (current != null) {
                        GalleryScreen(current) { gallery = null }
                    } else {
                        PipelineScreen(onOpenGallery = { gallery = it })
                    }
                }
            }
        }
    }
}

@Composable
fun PipelineScreen(
    vm: PipelineViewModel = viewModel(),
    onOpenGallery: (ConversionResult) -> Unit = {},
) {
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris -> vm.start(uris) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PhotoConverter", style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !state.running,
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            ) { Text("Pick photos") }
            OutlinedButton(
                enabled = !state.running,
                onClick = { vm.cleanup() },
            ) { Text("Clean up temp files") }
        }

        state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (state.running && state.phaseLabel != null) {
            PhaseProgress(state.phaseLabel!!, state.progressDone, state.progressTotal)
        }

        state.analysis?.let { AnalysisCard(it) }
        state.heifToJpeg?.let { ConversionCard(it, onOpenGallery) }
        state.jpegToHeif?.let { ConversionCard(it, onOpenGallery) }
    }
}

@Composable
private fun PhaseProgress(label: String, done: Int, total: Int) {
    Column {
        Text("$label  $done / $total")
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AnalysisCard(analysis: AnalysisResult) {
    var showImages by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Analysis", style = MaterialTheme.typography.titleMedium)
            Text("${analysis.stats.count} images in ${analysis.stats.totalMs} ms " +
                "(avg ${analysis.stats.avgMs} ms/image)")
            HorizontalDivider()
            Text("JPEG: ${analysis.jpegs.size}    HEIF: ${analysis.heifs.size}    Other: ${analysis.others.size}")
            Text(
                if (showImages) "Hide details ▲" else "Show details ▼",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { showImages = !showImages },
            )
            if (showImages) {
                analysis.images.forEach {
                    Text(it.summaryLine(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConversionCard(result: ConversionResult, onOpenGallery: (ConversionResult) -> Unit = {}) {
    var dialogIssues by remember { mutableStateOf<Pair<String, List<FileIssue>>?>(null) }
    val hasOutputs = result.outputs.any { it.source != null }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasOutputs) { onOpenGallery(result) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.label, style = MaterialTheme.typography.titleMedium)
            Text("${result.succeeded} / ${result.stats.count} converted in ${result.stats.totalMs} ms " +
                "(avg ${result.stats.avgMs} ms/image)")
            if (hasOutputs) {
                Text("Tap to compare original vs converted", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Errors: ${result.errors.size}",
                    color = if (result.errors.isEmpty()) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(enabled = result.errors.isNotEmpty()) {
                        dialogIssues = "${result.label} errors" to result.errors
                    },
                )
                Text(
                    "Warnings: ${result.warnings.size}",
                    modifier = Modifier.clickable(enabled = result.warnings.isNotEmpty()) {
                        dialogIssues = "${result.label} warnings" to result.warnings
                    },
                )
            }
        }
    }

    dialogIssues?.let { (title, issues) ->
        AlertDialog(
            onDismissRequest = { dialogIssues = null },
            confirmButton = { TextButton(onClick = { dialogIssues = null }) { Text("Close") } },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(issues) { issue ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(issue.name, style = MaterialTheme.typography.labelLarge)
                            Text(issue.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
        )
    }
}
