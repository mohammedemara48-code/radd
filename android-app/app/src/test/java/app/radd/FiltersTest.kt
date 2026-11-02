package app.radd

import app.radd.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class FiltersTest {
    private val now = 1_800_000_000_000L
    private fun item(id: Long, source: Source, kb: Long, ageDays: Int, q: Quality = Quality.INTACT) =
        RecoverableItem(id, "$id.jpg", source, q, kb * 1024, now - ageDays * DAY_MS, 100, 100, "/x/$id.jpg")

    private val items = listOf(
        item(1, Source.WHATSAPP, 300, 2), item(2, Source.WHATSAPP, 40, 40), item(3, Source.TRASH, 2000, 10, Quality.PARTIAL),
        item(4, Source.THUMBNAILS, 12, 5), item(5, Source.TRASH, 150, 400),
    )

    @Test fun noFiltersKeepsEverythingNewestFirst() {
        assertEquals(listOf(1L, 4L, 3L, 2L, 5L), Filters().apply(items, now).map { it.id })
    }

    @Test fun sourceSizeDateAndQualityCombine() {
        assertEquals(listOf(1L), Filters(source = Source.WHATSAPP, size = SizeFilter.OVER_100K, date = DateFilter.WEEK).apply(items, now).map { it.id })
        assertEquals(listOf(3L), Filters(quality = QualityFilter.PARTIAL_ONLY).apply(items, now).map { it.id })
        assertEquals(listOf(3L, 1L, 5L), Filters(size = SizeFilter.OVER_100K, sort = SortOrder.LARGEST).apply(items, now).map { it.id })
    }

    @Test fun smallBucketExcludesTheBoundary() {
        assertEquals(listOf(4L, 2L), Filters(size = SizeFilter.SMALL).apply(items, now).map { it.id })
        assertEquals(2, Filters(source = Source.TRASH, date = DateFilter.MONTH).activeCount.let { it })
    }

    @Test fun formatting() {
        assertEquals("815 ك.ب", formatSize(815 * 1024L))
        assertEquals("1.5 م.ب", formatSize(1_572_864L))
        assertEquals("02:41", formatElapsed(161))
    }
}
