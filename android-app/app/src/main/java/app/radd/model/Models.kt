package app.radd.model

import java.time.Instant
import java.time.ZoneId

/** Where a recoverable photo was found. Every source is a place that really keeps traces on a normal, un-rooted phone. */
enum class Source(val label: String, val hint: String) {
    TRASH("سلة المحذوفات", "اتمسحت من المعرض وفاضلة في سلة المحذوفات."),
    LOST_DIR("LOST.DIR", "بقايا ملفات لقيناها بعد خطأ في الذاكرة."),
    THUMBNAILS("المصغّرات", "نسخ مصغّرة، والصورة الأصلية بتاعتها مش في المعرض."),
    WHATSAPP("واتساب", "صور واتساب مش موجودة في المعرض."),
    TELEGRAM("تيليجرام", "صور تيليجرام مش موجودة في المعرض."),
    HIDDEN("مخفية", "صور في مجلدات مخفية، شكلها صور كاميرا."),
    CARVED("من الكاش", "صور لقيناها جوه ملفات مؤقتة، شكلها صور كاميرا."),
}

/** What the last scan left out, so an empty or short list can be explained instead of looking like a bug. */
data class ScanSummary(val stillVisible: Int, val excluded: Int) {
    val hasNumbers: Boolean get() = stillVisible > 0 || excluded > 0

    fun sentence(): String = buildString {
        append("استبعدنا")
        if (stillVisible > 0) append(" $stillVisible صورة لسه موجودة عندك")
        if (stillVisible > 0 && excluded > 0) append(" و")
        if (excluded > 0) append(" $excluded صورة صغيرة أو أيقونة أو بتاعة تطبيقات")
        append(".")
    }
}

/** INTACT: the file decodes end to end. PARTIAL: the header is valid but data is missing (often a grey band at the bottom). */
enum class Quality(val label: String) { INTACT("سليمة"), PARTIAL("ناقصة") }

data class RecoverableItem(
    val id: Long,
    val name: String,
    val source: Source,
    val quality: Quality,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val width: Int,
    val height: Int,
    val originPath: String,
    val format: String = "jpg",
    /** Where the bytes live: [length] bytes starting at [offset] inside [originPath]. */
    val offset: Long = 0,
    val length: Long = sizeBytes,
    val sha256: String = "",
)

enum class SizeFilter(val label: String, val minBytes: Long, val maxBytes: Long) {
    ANY("أي حجم", 0, Long.MAX_VALUE),
    SMALL("أقل من 100 ك.ب", 0, 100 * 1024L),
    OVER_100K("أكبر من 100 ك.ب", 100 * 1024L, Long.MAX_VALUE),
    OVER_1M("أكبر من 1 م.ب", 1024 * 1024L, Long.MAX_VALUE),
}

enum class DateFilter(val label: String, val days: Int) {
    ANY("أي وقت", Int.MAX_VALUE),
    WEEK("آخر 7 أيام", 7),
    MONTH("آخر 30 يوم", 30),
    YEAR("آخر سنة", 365),
}

enum class QualityFilter(val label: String) { ALL("الكل"), INTACT_ONLY("السليمة بس"), PARTIAL_ONLY("الناقصة بس") }

enum class SortOrder(val label: String) { NEWEST("الأحدث"), LARGEST("الأكبر"), SOURCE("المصدر") }

data class Filters(
    val source: Source? = null,
    val size: SizeFilter = SizeFilter.ANY,
    val date: DateFilter = DateFilter.ANY,
    val quality: QualityFilter = QualityFilter.ALL,
    val sort: SortOrder = SortOrder.NEWEST,
) {
    val activeCount: Int get() = listOf(source != null, size != SizeFilter.ANY, date != DateFilter.ANY, quality != QualityFilter.ALL).count { it }

    fun matches(item: RecoverableItem, nowMs: Long): Boolean {
        if (source != null && item.source != source) return false
        if (item.sizeBytes < size.minBytes || item.sizeBytes >= size.maxBytes) return false
        if (date != DateFilter.ANY && item.modifiedMs < nowMs - date.days * DAY_MS) return false
        return when (quality) {
            QualityFilter.ALL -> true
            QualityFilter.INTACT_ONLY -> item.quality == Quality.INTACT
            QualityFilter.PARTIAL_ONLY -> item.quality == Quality.PARTIAL
        }
    }

    fun apply(items: List<RecoverableItem>, nowMs: Long): List<RecoverableItem> {
        val kept = items.filter { matches(it, nowMs) }
        return when (sort) {
            SortOrder.NEWEST -> kept.sortedByDescending { it.modifiedMs }
            SortOrder.LARGEST -> kept.sortedByDescending { it.sizeBytes }
            SortOrder.SOURCE -> kept.sortedWith(compareBy<RecoverableItem> { it.source.ordinal }.thenByDescending { it.modifiedMs })
        }
    }
}

const val DAY_MS = 86_400_000L

// ---- Scan progress -----------------------------------------------------------------------------

enum class StageStatus { PENDING, RUNNING, DONE }

data class StageProgress(val source: Source, val status: StageStatus, val found: Int)

data class ScanProgress(
    val stages: List<StageProgress>,
    val percent: Float,
    val filesSeen: Int,
    val found: Int,
    val elapsedSec: Int,
    val currentPath: String,
    val latest: List<RecoverableItem>,
) {
    val stageNumber: Int get() = (stages.indexOfFirst { it.status == StageStatus.RUNNING } + 1).coerceAtLeast(1)
}

// ---- Restore result ----------------------------------------------------------------------------

data class RestoreResult(
    val saved: List<RecoverableItem>,
    val skippedDuplicates: Int,
    val failed: Int,
    val folderPath: String,
    val renamed: Int,
)

// ---- Formatting --------------------------------------------------------------------------------

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes ب"
    bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} ك.ب"
    else -> String.format(java.util.Locale.US, "%.1f م.ب", bytes / (1024.0 * 1024.0))
}

private val MONTHS = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")

fun formatDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
}

fun formatTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    return String.format(java.util.Locale.US, "%d:%02d %s", h12, t.minute, if (t.hour < 12) "ص" else "م")
}

fun formatElapsed(sec: Int): String = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60)
