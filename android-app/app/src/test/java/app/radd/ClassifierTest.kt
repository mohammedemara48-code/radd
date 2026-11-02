package app.radd

import app.radd.engine.Classifier
import app.radd.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierTest {
    private fun kindOf(vararg segments: String): Classifier.DirKind {
        var k: Classifier.DirKind? = null
        for (i in segments.indices) k = Classifier.dirKind(segments.take(i + 1).map { it.lowercase() }, k, false)
        return k ?: Classifier.DirKind(false, false, false, null, false, false)
    }

    private fun classify(name: String, vararg dirs: String, size: Long = 50_000): Source? = Classifier.classify(name, kindOf(*dirs), size)

    @Test fun trashAndPendingFiles() {
        assertEquals(Source.TRASH, classify(".trashed-1763900000-IMG_1.jpg", "DCIM", "Camera"))
        assertEquals(Source.TRASH, classify(".pending-1763900000-IMG_1.jpg", "Pictures"))
        assertEquals(Source.TRASH, classify("IMG_2.jpg", "DCIM", ".Trash"))
        assertEquals(Source.TRASH, classify("x", "Pictures", ".trash"))
    }

    @Test fun lostDirThumbnailsAndMessengers() {
        assertEquals(Source.LOST_DIR, classify("#1234", "LOST.DIR"))
        assertEquals(Source.THUMBNAILS, classify(".thumbdata3--1967", "DCIM", ".thumbnails"))
        assertEquals(Source.THUMBNAILS, classify(".thumbdata4--5", "DCIM"))
        assertEquals(Source.WHATSAPP, classify("IMG-1-WA0001.jpg", "Android", "media", "com.whatsapp", "WhatsApp", "Media", "WhatsApp Images", "Sent"))
        assertEquals(Source.WHATSAPP, classify("a.jpg", "Android", "media", "com.whatsapp.w4b", "WhatsApp Business", "Media", "WhatsApp Business Images"))
        assertEquals(Source.WHATSAPP, classify("old.jpg", "WhatsApp", "Media", "WhatsApp Images"))
        assertEquals(Source.TELEGRAM, classify("1_2.jpg", "Android", "media", "org.telegram.messenger", "Telegram", "Telegram Images"))
        assertNull(classify("v.mp4", "Android", "media", "com.whatsapp", "WhatsApp", "Media", "WhatsApp Video"))
        assertNull(classify("clip.jpg", "Android", "media", "com.whatsapp", "WhatsApp", "Media", "WhatsApp Video"))
    }

    @Test fun hiddenAndCarvedAndIgnored() {
        assertEquals(Source.HIDDEN, classify("a.jpg", "Pictures", ".private"))
        assertEquals(Source.HIDDEN, classify(".secret.png", "Pictures"))
        assertEquals(Source.CARVED, classify("blob.bin", "Download", "exports"))
        assertEquals(Source.CARVED, classify("cache.dat", "Download"))
        assertNull("a normal visible photo is not lost", classify("IMG_1.jpg", "DCIM", "Camera"))
        assertNull(classify("movie.mp4", "Movies"))
        assertNull(classify("tiny.bin", "Download", size = 100))
        assertNull(classify("notes.txt", "Documents"))
    }

    @Test fun nomediaFolderCountsAsHidden() {
        val k = Classifier.dirKind(listOf("pictures", "stash"), null, hasNoMedia = true)
        assertEquals(Source.HIDDEN, Classifier.classify("a.jpg", k, 50_000))
    }

    @Test fun appFoldersAreNeverCandidates() {
        assertNull("another app's cache", classify("blob.bin", "Download", ".cache"))
        assertNull("package-named folder", classify("pic.jpg", "com.supercell.clashofclans", "files"))
        assertNull("emoji pack", classify("smile.jpg", "Pictures", ".emoji"))
        assertNull("sticker folder", classify("a.jpg", "Pictures", ".stickers"))
        assertNull("game assets", classify("tex.jpg", "Games", "Unity", "textures"))
        assertNull("private app storage", classify("a.jpg", "Android", "sandbox", "x"))
        assertNull("another app's media folder", classify("a.jpg", "Android", "media", "com.some.game", "files"))
        assertNull("icon by file name", classify("ic_launcher.png", "Pictures", ".private"))
        assertNull("sticker by file name", classify("sticker_4.webp", "Pictures", ".private"))
    }

    @Test fun chatFoldersTheUserStillHasAreNotCandidates() {
        val wa = arrayOf("Android", "media", "com.whatsapp", "WhatsApp", "Media")
        assertNull(classify("a.jpg", *wa, "WhatsApp Images", "Private"))
        assertNull(classify("a.jpg", *wa, ".Statuses"))
        assertNull(classify("a.webp", *wa, "WhatsApp Stickers"))
        assertNull(classify("a.jpg", *wa, "WhatsApp Profile Photos"))
        // a chat folder the user hid from the gallery (.nomedia) is hidden on purpose, not deleted
        var k: Classifier.DirKind? = null
        val segs = listOf("android", "media", "com.whatsapp", "whatsapp", "media", "whatsapp images")
        for (i in segs.indices) k = Classifier.dirKind(segs.take(i + 1), k, hasNoMedia = i == segs.lastIndex)
        assertNull(Classifier.classify("a.jpg", k!!, 50_000))
    }

    @Test fun tenKilobytesIsTheFloor() {
        assertNull(classify("a.jpg", "Pictures", ".private", size = 9_000))
        assertEquals(Source.HIDDEN, classify("a.jpg", "Pictures", ".private", size = 11_000))
        assertNull(classify("#1", "LOST.DIR", size = 9_000))
        assertEquals(Source.THUMBNAILS, classify(".thumbdata3--1", "DCIM", ".thumbnails", size = 5_000))
    }
}
