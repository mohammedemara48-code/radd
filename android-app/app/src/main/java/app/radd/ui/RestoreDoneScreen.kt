package app.radd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.radd.model.*

@Composable
fun RestoreDoneScreen(result: RestoreResult, thumb: ThumbSlot, onOpenFolder: () -> Unit, onBack: () -> Unit, onNewScan: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("اتسترجعت ${result.saved.size} صور", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("ودلوقتي ظاهرة في المعرض", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }

        // Where
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("اتحفظت في", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PathText(result.folderPath, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Result("اتحفظت", result.saved.size, MaterialTheme.colorScheme.primary)
                    Result("مكررة اتخطّت", result.skippedDuplicates, MaterialTheme.colorScheme.onSurface)
                    Result("اتغيّر اسمها", result.renamed, MaterialTheme.colorScheme.onSurface)
                    Result("فشلت", result.failed, if (result.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // Thumbs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            result.saved.take(5).forEach { item ->
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                    thumb(item, Modifier.fillMaxSize())
                    if (item.quality == Quality.PARTIAL) QualityTag(item.quality, Modifier.align(Alignment.BottomEnd).padding(3.dp))
                }
            }
        }

        HonestNote(
            "اتأكد بنفسك",
            "مفيش ملف موجود اتكتب فوقه، والأسماء المتكررة اتزوّد عليها رقم. الصور «الناقصة» هتلاقي فيها جزء رمادي، وده أقصى ما بقى منها.",
            tone = NoteTone.INFO,
        )

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onOpenFolder, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("افتح المجلد") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onBack, Modifier.weight(1f).height(48.dp)) { Text("رجوع للنتائج") }
                OutlinedButton(onNewScan, Modifier.weight(1f).height(48.dp)) { Text("فحص جديد") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Result(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
