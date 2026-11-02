package app.radd

import app.radd.model.*
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** Fixed clock and deterministic sample scan so the previews are reproducible. */
val PreviewZone: ZoneId = ZoneId.of("Asia/Riyadh")
val PreviewNow: Long = ZonedDateTime.of(2026, 11, 3, 0, 40, 0, 0, PreviewZone).toInstant().toEpochMilli()

private val counts = listOf(
    Source.TRASH to 23, Source.WHATSAPP to 62, Source.TELEGRAM to 18, Source.THUMBNAILS to 31,
    Source.LOST_DIR to 9, Source.HIDDEN to 17, Source.CARVED to 24,
)

private fun path(source: Source, rnd: Random, n: Int, day: String): String {
    val root = "/storage/emulated/0"
    return when (source) {
        Source.TRASH -> "$root/DCIM/Camera/.trashed-17${rnd.nextInt(10, 99)}${rnd.nextInt(100000, 999999)}-IMG_${day}_${rnd.nextInt(100000, 235959)}.jpg"
        Source.WHATSAPP -> "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/${if (n % 3 == 0) "Sent/" else ""}IMG-$day-WA${"%04d".format(n)}.jpg"
        Source.TELEGRAM -> "$root/Android/media/org.telegram.messenger/Telegram/Telegram Images/${rnd.nextLong(1000000000, 9999999999)}_${rnd.nextInt(100, 999)}.jpg"
        Source.THUMBNAILS -> "$root/DCIM/.thumbnails/.thumbdata3--196729${rnd.nextInt(1000, 9999)}"
        Source.LOST_DIR -> "$root/LOST.DIR/#${rnd.nextInt(10000, 99999)}"
        Source.HIDDEN -> "$root/Pictures/.private/.${rnd.nextInt(100000, 999999)}.jpg"
        Source.CARVED -> "$root/Download/.cache/blob-${rnd.nextInt(0x1000, 0xffff).toString(16)}.bin"
    }
}

val SampleItems: List<RecoverableItem> = run {
    val rnd = Random(2026)
    var id = 0L
    counts.flatMap { (source, n) ->
        List(n) { i ->
            id++
            val ageDays = when (source) { Source.THUMBNAILS, Source.CARVED -> rnd.nextInt(5, 240); Source.TRASH -> rnd.nextInt(0, 28); else -> rnd.nextInt(0, 160) }
            val ms = PreviewNow - ageDays * DAY_MS - rnd.nextLong(0, DAY_MS)
            val day = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), PreviewZone).let { "%04d%02d%02d".format(it.year, it.monthValue, it.dayOfMonth) }
            val size = when (source) {
                Source.THUMBNAILS -> rnd.nextLong(6, 70) * 1024
                Source.CARVED -> rnd.nextLong(30, 600) * 1024
                Source.TRASH -> rnd.nextLong(900, 4200) * 1024
                Source.LOST_DIR, Source.HIDDEN -> rnd.nextLong(200, 2800) * 1024
                else -> rnd.nextLong(45, 900) * 1024
            }
            val partial = when (source) { Source.CARVED -> rnd.nextInt(100) < 55; Source.THUMBNAILS -> rnd.nextInt(100) < 10; Source.LOST_DIR -> rnd.nextInt(100) < 30; else -> rnd.nextInt(100) < 6 }
            val portrait = rnd.nextBoolean()
            val p = path(source, rnd, i + 20, day)
            RecoverableItem(
                id = id, name = p.substringAfterLast('/').removePrefix(".").let { if (source == Source.THUMBNAILS || source == Source.CARVED || source == Source.LOST_DIR) "radd-${source.name.lowercase()}-$day-${i + 1}.jpg" else it },
                source = source, quality = if (partial) Quality.PARTIAL else Quality.INTACT, sizeBytes = size, modifiedMs = ms,
                width = if (source == Source.THUMBNAILS) 512 else if (portrait) 3000 else 4000,
                height = if (source == Source.THUMBNAILS) 384 else if (portrait) 4000 else 3000,
                originPath = p,
            )
        }
    }
}
