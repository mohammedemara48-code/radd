package app.radd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.radd.model.Source

data class PermissionUi(val allFilesGranted: Boolean, val mediaGranted: Boolean) {
    val ready: Boolean get() = allFilesGranted && mediaGranted
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(permission: PermissionUi, onGrant: () -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RaddMark()
            Spacer(Modifier.width(10.dp))
            Text("ردّ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            InfoChip("أوفلاين · مفيش إنترنت", Icons.Filled.WifiOff)
        }

        // Hero
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("هات صورك اللي اتمسحت", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "بيدوّر جوه موبايلك على الصور اللي لسه ليها أثر، من غير إنترنت.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(width = 96.dp, height = 104.dp)) {
                    HeroPhoto(seed = 1, rot = -9f, modifier = Modifier.align(Alignment.TopStart))
                    HeroPhoto(seed = 0, rot = 7f, modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
        }

        // Permissions
        Column {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp, modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))) {
                Column {
                    PermissionRow(
                        Icons.Filled.FolderOpen, "إدارة كل الملفات",
                        "للمجلدات المخفية وسلة المحذوفات.", permission.allFilesGranted,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionRow(
                        Icons.Filled.PhotoLibrary, "صور المعرض",
                        "لسلة المحذوفات في المعرض.", permission.mediaGranted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (permission.ready) {
                Button(onStart, Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.Search, null); Spacer(Modifier.width(8.dp)); Text("ابدأ الفحص", style = MaterialTheme.typography.titleSmall)
                }
            } else {
                Button(onGrant, Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.LockOpen, null); Spacer(Modifier.width(8.dp)); Text("السماح بالوصول", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        // Sources
        Section("هيدوّر فين") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Source.TRASH, Source.WHATSAPP, Source.TELEGRAM, Source.THUMBNAILS, Source.LOST_DIR, Source.HIDDEN, Source.CARVED).forEach {
                    InfoChip(it.label, it.icon)
                }
            }
        }

        HonestNote(
            "الصراحة قبل ما تبدأ",
            "ردّ مش بيقرا قطاعات الفلاش اللي اتمسحت فعلاً (ده محتاج روت). بيلاقي بس اللي لسه ليه أثر، فمفيش ضمان لكل صورة.",
        )

        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun HeroPhoto(seed: Int, rot: Float, modifier: Modifier) {
    Box(
        modifier.size(62.dp, 78.dp).rotate(rot).clip(RoundedCornerShape(10.dp))
            .border(3.dp, androidx.compose.ui.graphics.Color.White, RoundedCornerShape(10.dp)),
    ) { ProceduralPhoto(seed) }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Icon(Icons.Filled.CheckCircle, "تمام", tint = MaterialTheme.colorScheme.primary)
        } else {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("مطلوب", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}
