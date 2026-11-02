package app.radd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.radd.model.Quality
import app.radd.model.RecoverableItem
import app.radd.model.Source

val Source.icon: ImageVector
    get() = when (this) {
        Source.TRASH -> Icons.Filled.DeleteSweep
        Source.LOST_DIR -> Icons.Filled.FolderOff
        Source.THUMBNAILS -> Icons.Filled.PhotoSizeSelectSmall
        Source.WHATSAPP -> Icons.Filled.Chat
        Source.TELEGRAM -> Icons.Filled.Send
        Source.HIDDEN -> Icons.Filled.VisibilityOff
        Source.CARVED -> Icons.Filled.ContentCut
    }

/** Slot that draws a thumbnail for an item. In the app this loads a downsampled bitmap; previews draw a procedural scene. */
typealias ThumbSlot = @Composable (RecoverableItem, Modifier) -> Unit

/** File paths and names are always left-to-right, even inside an RTL screen. */
@Composable
fun Ltr(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)

@Composable
fun PathText(path: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = 1) {
    Ltr {
        Text(
            path, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start,
        )
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

enum class NoteTone { INFO, WARN }

/** The honesty strip: states a real limit in plain words. */
@Composable
fun HonestNote(title: String, body: String, modifier: Modifier = Modifier, tone: NoteTone = NoteTone.WARN) {
    val bg = if (tone == NoteTone.WARN) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (tone == NoteTone.WARN) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = bg) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (tone == NoteTone.WARN) Icons.Outlined.Info else Icons.Outlined.Lock, null, tint = fg, modifier = Modifier.size(22.dp).padding(top = 2.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = fg)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = fg)
            }
        }
    }
}

@Composable
fun QualityTag(quality: Quality, modifier: Modifier = Modifier) {
    val partial = quality == Quality.PARTIAL
    Surface(
        modifier, shape = RoundedCornerShape(6.dp),
        color = if (partial) Amber else Color(0xCC0F5C4D),
    ) {
        Text(
            quality.label, Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = if (partial) Color(0xFF2B1700) else Color.White,
        )
    }
}

@Composable
fun SourceBadge(source: Source, modifier: Modifier = Modifier) {
    Box(modifier.size(20.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
        Icon(source.icon, null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

@Composable
fun SelectDot(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Box(modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(2.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(modifier.size(22.dp).background(Color(0x33000000), CircleShape))
    }
}

/** App mark used in headers. */
@Composable
fun RaddMark(modifier: Modifier = Modifier, size: Int = 40) {
    Box(modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape((size * 0.28f).dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Restore, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size((size * 0.58f).dp))
    }
}

@Composable
fun PrimaryBorder() = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)

/** Small read-only chip (not a button), 30dp tall. */
@Composable
fun InfoChip(label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
