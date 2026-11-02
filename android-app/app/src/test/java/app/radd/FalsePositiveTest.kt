package app.radd

import app.radd.engine.*
import app.radd.model.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Mo's real phone listed 68,452 "recovered" photos: the gallery's own photos, app icons and cache images. This is a phone built
 * the same way: a gallery full of live photos, chat copies of them, thumbnails of them, hundreds of icons and app assets,
 * and exactly seven photos that really were deleted. The scan must return those seven and nothing else.
 */
class FalsePositiveTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var gallery: MediaGateway
    private val liveScenes = listOf(1, 2, 3, 7, 8)

    /** The seven genuinely deleted photos and where each lives, by the sha of its bytes. */
    private val expected = HashMap<String, Source>()

    private fun put(path: String, vararg parts: ByteArray): File = Fixtures.write(File(root, path), *parts)

    @Before fun phone() {
        root = tmp.newFolder("phone")
        val rows = ArrayList<MediaRow>()
        for (n in liveScenes) {
            val f = put("DCIM/Camera/IMG_2025_$n.jpg", Fixtures.scene(n))
            rows += MediaRow(f.path, f.length(), f.lastModified(), 720, 480)
        }
        gallery = object : MediaGateway {
            override fun trashed() = MediaQuery(emptyList())
            override fun visible() = MediaQuery(rows)
        }

        // ---- deleted photos: the only things that may be reported ----
        fun real(source: Source, bytes: ByteArray) { expected[Fixtures.sha(bytes)] = source }
        put("DCIM/Camera/.trashed-1763900000-IMG_4.jpg", Fixtures.scene(4)); real(Source.TRASH, Fixtures.scene(4))
        put("LOST.DIR/#1234", Fixtures.scene(5)); real(Source.LOST_DIR, Fixtures.scene(5))
        put("DCIM/.thumbnails/.thumbdata4--77", Fixtures.thumb(9)); real(Source.THUMBNAILS, Fixtures.thumb(9))  // original is gone
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20250601-WA0009.jpg", Fixtures.scene(12)); real(Source.WHATSAPP, Fixtures.scene(12))
        put("Android/media/org.telegram.messenger/Telegram/Telegram Images/5_1.jpg", Fixtures.scene(10)); real(Source.TELEGRAM, Fixtures.scene(10))
        put("Pictures/.vault/holiday.jpg", Fixtures.named("big_noexif.jpg")); real(Source.HIDDEN, Fixtures.named("big_noexif.jpg"))
        put("Download/export/stuff.tmp", Fixtures.junk(4000, 1), Fixtures.scene(6), Fixtures.junk(3000, 2)); real(Source.CARVED, Fixtures.scene(6))

        // ---- things that exist or are not photos: all must be ignored ----
        // chat copies of live photos (WhatsApp and Telegram recompress them)
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-WA0001.jpg", Fixtures.waCopy(1))
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-WA0002.jpg", Fixtures.waCopy(2))
        put("Android/media/org.telegram.messenger/Telegram/Telegram Images/9_1.jpg", Fixtures.waCopy(8))
        put("Android/media/org.telegram.messenger/Telegram/Telegram Images/9_2.jpg", Fixtures.waCopy(3))
        // thumbnails of live photos, and of the trashed one (already listed)
        put("DCIM/.thumbnails/.thumbdata3--5", Fixtures.thumb(1), Fixtures.junk(200, 1), Fixtures.thumb(2), Fixtures.junk(200, 2), Fixtures.thumb(7), Fixtures.junk(200, 3), Fixtures.thumb(4))
        // copies of live photos hidden in other folders: same bytes, and the same picture with different bytes
        put("Pictures/.vault/copy_exact.jpg", Fixtures.scene(1))
        for (i in 1..40) put("Pictures/.vault/copy_$i.jpg", Fixtures.scene(liveScenes[i % liveScenes.size], 500 + i))
        // chat media that is not a photo or that the user still has in a chat
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private/IMG-private.jpg", Fixtures.scene(11))
        put("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/status.jpg", Fixtures.scene(11, 3))
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/s.webp", Fixtures.webp(1, 512, 512, 20_000))
        put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/sticker_9.webp", Fixtures.webp(2, 512, 512, 20_000))
        // icons, sprites and UI assets
        for (i in 1..200) put("Pictures/.assets_$i/icon_$i.png", Fixtures.named("icon_96.png"))
        for (i in 1..60) put("Pictures/.hidden/ui/a$i.png", Fixtures.named("launcher_192.png"))
        put("Pictures/.stash/ui_asset.png", Fixtures.named("ui_asset_300.png"))           // 300 px, no EXIF: not a photo
        put("Pictures/.stash/tiny.jpg", Fixtures.named("tiny_noisy.jpg"))                 // 150 px
        put("Pictures/.stash/banner.jpg", Fixtures.named("banner.jpg"))                   // 5:1 strip
        put("Pictures/.stash/small_file.jpg", Fixtures.named("smooth_small.jpg"))         // under 10 KB
        put("Pictures/.stash/flat.png", Fixtures.named("flat_400.png"))
        put("Pictures/.stash/scene_without_exif.jpg", Fixtures.scene(11))                 // 720x480, not camera, not large
        put("Pictures/.stash/ic_launcher_round.jpg", Fixtures.scene(5, 77))               // named like an icon
        // game and app assets
        put("Games/Unity/.nomedia")
        put("Games/Unity/textures/tex1.jpg", Fixtures.scene(11, 1))
        put("com.supercell.clashofclans/files/pic.jpg", Fixtures.scene(10, 21))
        put(".facebook_cache/img1.jpg", Fixtures.scene(10, 22))
        put("Android/media/com.some.game/files/bg.jpg", Fixtures.scene(10, 23))
        put("Android/data/com.some.app/files/secret.jpg", Fixtures.scene(10, 24))
        put("Download/.cache/blob.bin", Fixtures.junk(900, 4), Fixtures.scene(10, 25))
        put("Pictures/.thumbnails_app/cache/x.jpg", Fixtures.scene(10, 26))
        // our own output and plain visible photos
        put("RaddRecovered/restored.jpg", Fixtures.scene(4, 31))
        put("Pictures/Screenshots/shot.jpg", Fixtures.scene(9, 41))
        // bulk: one hundred icon-sized PNGs in a messenger folder (under the size and dimension gates)
        for (i in 1..100) put("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/ic_$i.png", Fixtures.named("icon_96.png"))
    }

    private fun scan() = runBlocking { ScanEngine(StorageRoots { listOf(root) }, gallery, DecodeCheck { _, _, _, _ -> true }, Fixtures.fingerprinter).run().toList() }

    @Test fun onlyTheSevenDeletedPhotosAreReported() {
        val events = scan()
        val found = events.filterIsInstance<ScanEvent.FoundOne>().map { it.item }
        val falsePositives = found.filter { it.sha256 !in expected.keys || it.source != expected[it.sha256] }
        assertEquals("false positives: " + falsePositives.map { "${it.source} ${File(it.path).name}" }, 0, falsePositives.size)
        val missing = expected.filterKeys { it !in found.map { f -> f.sha256 } }
        assertTrue("missing: $missing; found: " + found.map { "${it.source} ${File(it.path).name}" }, missing.isEmpty())
        assertEquals(7, found.size)
        val finished = events.last() as ScanEvent.Finished
        assertEquals(7, finished.found)
        assertTrue("copies of live photos were counted as such: ${finished.stillVisible}", finished.stillVisible >= 40)
        assertTrue("small files, icons and assets were counted as excluded: ${finished.excluded}", finished.excluded >= 5)
    }

    @Test fun aPhoneWithNothingDeletedReportsNothing() {
        // remove the seven real ones: what is left is a normal, healthy phone
        File(root, "DCIM/Camera/.trashed-1763900000-IMG_4.jpg").delete()
        File(root, "LOST.DIR").deleteRecursively()
        File(root, "DCIM/.thumbnails/.thumbdata4--77").delete()
        put("DCIM/.thumbnails/.thumbdata3--5", Fixtures.thumb(1), Fixtures.junk(200, 1), Fixtures.thumb(2), Fixtures.junk(200, 2), Fixtures.thumb(7)) // only live ones
        File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20250601-WA0009.jpg").delete()
        File(root, "Android/media/org.telegram.messenger/Telegram/Telegram Images/5_1.jpg").delete()
        File(root, "Pictures/.vault/holiday.jpg").delete()
        File(root, "Download/export").deleteRecursively()
        val events = scan()
        val leftovers = events.filterIsInstance<ScanEvent.FoundOne>().map { "${it.item.source} ${it.item.path.removePrefix(root.path)}" }
        assertTrue("leftovers: $leftovers", leftovers.isEmpty())
        assertEquals(0, (events.last() as ScanEvent.Finished).found)
    }

    @Test fun withoutAnyGalleryInformationTheAssetsAreStillIgnored() {
        // MediaStore unreadable: copies of live photos may show up, but never an icon, sticker, banner or app asset
        val events = runBlocking { ScanEngine(StorageRoots { listOf(root) }, NoMedia, DecodeCheck { _, _, _, _ -> true }, Fixtures.fingerprinter).run().toList() }
        val found = events.filterIsInstance<ScanEvent.FoundOne>().map { it.item }
        val names = found.map { File(it.path).name }
        assertTrue("names $names", names.none { it.startsWith("icon_") || it.startsWith("ic_") || it.startsWith("a") && it.endsWith(".png") })
        assertTrue(found.none { "Games" in it.path || "com.supercell" in it.path || ".facebook_cache" in it.path || "com.some" in it.path })
        assertTrue(found.none { it.width < 200 && it.height < 200 || it.length < 4 * 1024 })
        assertTrue("found ${found.size}", found.size <= 7 + 5 + 41 + 10)
    }
}
