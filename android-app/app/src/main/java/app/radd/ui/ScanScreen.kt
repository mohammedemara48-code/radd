package app.radd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.radd.model.*
import java.util.Locale

@Composable
fun ScanScreen(progress: ScanProgress, thumb: ThumbSlot, onStop: () -> Unit, onShowResults: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(top = 8.dp)) {
            Text("بيفحص الذاكرة…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("المرحلة ${progress.stageNumber} من ${progress.stages.size}  ·  سيب التطبيق مفتوح", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Ring + counters
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 1f }, modifier = Modifier.fillMaxSize(), strokeWidth = 9.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    CircularProgressIndicator(
                        progress = { progress.percent }, modifier = Modifier.fillMaxSize(), strokeWidth = 9.dp,
                        color = MaterialTheme.colorScheme.primary, trackColor = androidx.compose.ui.graphics.Color.Transparent,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(progress.percent * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("صور اتلقت", String.format(Locale.US, "%,d", progress.found), big = true)
                    Stat("ملفات اتفحصت", String.format(Locale.US, "%,d", progress.filesSeen))
                    Stat("الوقت", formatElapsed(progress.elapsedSec))
                }
            }
        }

        // Stages
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))) {
            Column(Modifier.padding(vertical = 4.dp)) {
                progress.stages.forEach { StageRow(it) }
            }
        }

        // Current path
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            PathText(progress.currentPath, Modifier.weight(1f))
        }

        // Latest finds
        if (progress.latest.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                progress.latest.take(5).forEach { item ->
                    Box(Modifier.weight(1f).aspectRatio(1.7f).clip(RoundedCornerShape(10.dp))) { thumb(item, Modifier.fillMaxSize()) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onStop, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("إيقاف")
            }
            FilledTonalButton(onShowResults, Modifier.weight(1.4f).height(52.dp)) {
                Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("شوف النتائج")
            }
        }
            }
}

@Composable
private fun Stat(label: String, value: String, big: Boolean = false) {
    Column {
        Text(value, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (big) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StageRow(stage: StageProgress) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(stage.source.icon, null, Modifier.size(20.dp), tint = if (stage.status == StageStatus.PENDING) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Text(
            stage.source.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (stage.status == StageStatus.RUNNING) FontWeight.Bold else FontWeight.Normal,
            color = if (stage.status == StageStatus.PENDING) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        when (stage.status) {
            StageStatus.DONE -> {
                Text("${stage.found}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            StageStatus.RUNNING -> {
                Text("${stage.found}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(progress = { 0.6f }, Modifier.size(18.dp), strokeWidth = 2.5.dp)
            }
            StageStatus.PENDING -> Icon(Icons.Filled.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
