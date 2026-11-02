package app.radd.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.radd.model.*

private val Night = Color(0xFF0B100E)
private val NightCard = Color(0xFF17201C)

/** Full-screen viewer for one candidate, with honest metadata and a one-tap restore. */
@Composable
fun PhotoPreviewScreen(
    item: RecoverableItem, index: Int, total: Int, selected: Boolean, thumb: ThumbSlot,
    onClose: () -> Unit, onToggle: () -> Unit, onRestore: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Night)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.Close, "إغلاق", tint = Color.White) }
            Text("$index من $total", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleMedium)
            IconButton(onToggle) {
                Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, "تحديد", tint = if (selected) Color(0xFF8ED5BE) else Color.White)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            val ratio = item.width.toFloat() / item.height
            Box(Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(14.dp))) {
                thumb(item, Modifier.fillMaxSize())
            }
        }
        Surface(color = NightCard, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PathText(item.name, color = Color.White, maxLines = 1)
                        Text(
                            if (item.quality == Quality.INTACT) "سليمة · بتفتح كاملة" else "ناقصة · جزء منها ضاع",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (item.quality == Quality.INTACT) Color(0xFF8ED5BE) else Amber,
                        )
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0x22FFFFFF)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(item.source.icon, null, Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(item.source.label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Fact("الحجم", formatSize(item.sizeBytes), Modifier.weight(1f))
                    Fact("الأبعاد", "${item.width}×${item.height}", Modifier.weight(1f), ltr = true)
                    Fact("التاريخ", formatDate(item.modifiedMs), Modifier.weight(1.3f))
                }
                Column(Modifier.fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text("المسار الأصلي", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9DB0A6))
                    PathText(item.originPath, color = Color(0xFFDDE7E1), maxLines = 2)
                }
                Text(
                    "الاسترجاع بينسخ نسخة جديدة. الأصل مش هيتلمس.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9DB0A6),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onRestore, Modifier.weight(1f).height(52.dp)) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(8.dp)); Text("استرجع الصورة دي") }
                    OutlinedButton(onToggle, Modifier.height(52.dp)) { Text(if (selected) "إلغاء التحديد" else "تحديد", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier, ltr: Boolean = false) {
    Column(modifier.background(Color(0x14FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9DB0A6))
        val t = @Composable { Text(value, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1) }
        if (ltr) Ltr { t() } else t()
    }
}
