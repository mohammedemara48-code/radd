package app.radd.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.radd.model.*

/**
 * @param all everything the scan found (used for the per-source counts)
 * @param shown the items after [filters] are applied
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultsScreen(
    all: List<RecoverableItem>,
    shown: List<RecoverableItem>,
    filters: Filters,
    selected: Set<Long>,
    thumb: ThumbSlot,
    onFilters: (Filters) -> Unit,
    onOpen: (RecoverableItem) -> Unit,
    onToggle: (RecoverableItem) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    scanning: Boolean = false,
    onNewScan: () -> Unit = {},
    summary: ScanSummary? = null,
) {
    val selecting = selected.isNotEmpty()
    val intact = all.count { it.quality == Quality.INTACT }
    val groups = Source.entries.mapNotNull { s -> shown.filter { it.source == s }.takeIf { it.isNotEmpty() }?.let { s to it } }
    Column(modifier.fillMaxSize()) {
        // Top bar: normal or selection mode
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClearSelection) { Icon(Icons.Filled.Close, "إلغاء التحديد") }
                Text("${selected.size} محددة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onSelectAll) { Icon(Icons.Filled.SelectAll, null); Spacer(Modifier.width(6.dp)); Text("تحديد الكل (${shown.size})") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text(if (all.isEmpty()) "النتايج" else "${all.size} صورة ممسوحة لقيناها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (all.isNotEmpty()) {
        Text(
            "$intact سليمة · ${all.size - intact} ناقصة (الجزء الرمادي هو اللي ضاع)",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summary?.takeIf { it.hasNumbers }?.let {
            Text(it.sentence(), Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))

        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        // Source chips
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = filters.source == null, onClick = { onFilters(filters.copy(source = null)) },
                    label = { Text("الكل ${all.size}") },
                )
            }
            items(Source.entries.filter { s -> all.any { it.source == s } }) { s ->
                FilterChip(
                    selected = filters.source == s, onClick = { onFilters(filters.copy(source = s)) },
                    label = { Text("${s.label} ${all.count { it.source == s }}") },
                    leadingIcon = { Icon(s.icon, null, Modifier.size(16.dp)) },
                )
            }
        }
        // Size / date / quality / sort
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                DropChip("الحجم", filters.size != SizeFilter.ANY, if (filters.size == SizeFilter.ANY) "أي حجم" else filters.size.label,
                    SizeFilter.entries.map { it.label to { onFilters(filters.copy(size = it)) } })
            }
            item {
                DropChip("التاريخ", filters.date != DateFilter.ANY, if (filters.date == DateFilter.ANY) "أي وقت" else filters.date.label,
                    DateFilter.entries.map { it.label to { onFilters(filters.copy(date = it)) } })
            }
            item {
                DropChip("الحالة", filters.quality != QualityFilter.ALL, filters.quality.label,
                    QualityFilter.entries.map { it.label to { onFilters(filters.copy(quality = it)) } })
            }
            item { DropChip("ترتيب", false, filters.sort.label, SortOrder.entries.map { it.label to { onFilters(filters.copy(sort = it)) } }) }
        }
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ظاهر ${shown.size} من ${all.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (filters.activeCount > 0) TextButton({ onFilters(Filters(sort = filters.sort)) }) { Text("مسح الفلاتر (${filters.activeCount})") }
        }
        }

        if (scanning) {
            Text("الفحص لسه شغال، النتايج بتزيد. ", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (all.isEmpty()) {
            EmptyState(
                title = if (scanning) "لسه ملقيناش حاجة" else "ملقيناش صور ممسوحة",
                body = if (scanning) "الفحص شغال. أول ما يلاقي حاجة هتظهر هنا." else
                    "مفيش أثر لصور ممسوحة في الأماكن اللي بندوّر فيها. ده معناه غالبًا إن الصور اتمسحت نهائي، أو إنها لسه عندك. " +
                        (summary?.takeIf { it.hasNumbers }?.sentence() ?: ""),
                actionLabel = if (scanning) null else "رجوع", onAction = onNewScan, modifier = Modifier.weight(1f),
            )
        } else if (shown.isEmpty()) {
            EmptyState("مفيش نتايج بالفلاتر دي", "جرّب تمسح الفلاتر أو تغيّر الحجم أو التاريخ.", "مسح الفلاتر", { onFilters(Filters(sort = filters.sort)) }, Modifier.weight(1f))
        } else
        // Grid
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            groups.forEach { (source, list) ->
            item(key = "header-${source.name}", span = { GridItemSpan(maxLineSpan) }) { SourceHeader(source, list.size) }
            items(list, key = { it.id }) { item ->
                val isSel = item.id in selected
                Box(
                    Modifier.aspectRatio(1f).combinedClickable(
                        onClick = { if (selecting) onToggle(item) else onOpen(item) },
                        onLongClick = { onToggle(item) },
                    ),
                ) {
                    val inset = if (isSel) 10.dp else 0.dp
                    Box(
                        Modifier.fillMaxSize().padding(inset)
                            .clip(RoundedCornerShape(if (isSel) 10.dp else 4.dp))
                            .then(if (isSel) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier),
                    ) { thumb(item, Modifier.fillMaxSize()) }
                    if (selecting) SelectDot(isSel, Modifier.align(Alignment.TopStart).padding(6.dp))
                    SourceBadge(item.source, Modifier.align(Alignment.BottomStart).padding(start = inset + 4.dp, bottom = inset + 4.dp))
                    if (item.quality == Quality.PARTIAL) QualityTag(item.quality, Modifier.align(Alignment.BottomEnd).padding(end = inset + 4.dp, bottom = inset + 4.dp))
                }
            }
            }
        }

        // Restore bar
        if (selecting) {
            val total = shown.filter { it.id in selected }.sumOf { it.sizeBytes } + all.filter { it.id in selected && it !in shown }.sumOf { it.sizeBytes }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${selected.size} صور · ${formatSize(total)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("هتتنسخ في RaddRecovered. مفيش حاجة هتتكتب فوقها.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onRestore, Modifier.height(48.dp)) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(6.dp)); Text("استرجاع") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropChip(label: String, active: Boolean, value: String, options: List<Pair<String, () -> Unit>>) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active, onClick = { open = true },
            label = { Text(if (active) "$label: $value" else "$label · $value") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(open, { open = false }) {
            options.forEach { (text, action) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; action() }) }
        }
    }
}

@Composable
private fun SourceHeader(source: Source, count: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(source.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(source.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(source.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
