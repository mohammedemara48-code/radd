package app.radd

import app.radd.engine.*
import app.radd.model.Source
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private fun region(w: Int, h: Int, length: Long = 50_000, format: String = "jpg") = Region(format, 0, length, true, w, h)

    @Test fun smallImagesAreDropped() {
        assertNull(PhotoRules.shape(region(720, 480), Source.TRASH))
        assertEquals(Reject.TOO_SMALL, PhotoRules.shape(region(720, 480, length = 9_000), Source.TRASH))
        assertEquals(Reject.TOO_SMALL, PhotoRules.shape(region(199, 400), Source.TRASH))
        assertEquals(Reject.TOO_SMALL, PhotoRules.shape(region(96, 96), Source.CARVED))
        assertNull(PhotoRules.shape(region(200, 200), Source.LOST_DIR))
    }

    @Test fun stripsAndBannersAreNotPhotos() {
        assertEquals(Reject.WRONG_SHAPE, PhotoRules.shape(region(1600, 300), Source.HIDDEN))
        assertNull("a panorama is still a photo", PhotoRules.shape(region(3600, 1000), Source.TRASH))
    }

    @Test fun thumbnailsNeedOnlyOneLongSideButNotASquare() {
        assertNull(PhotoRules.shape(region(384, 256, length = 5_000), Source.THUMBNAILS))
        assertEquals(Reject.TOO_SMALL, PhotoRules.shape(region(160, 120, length = 5_000), Source.THUMBNAILS))
        assertEquals(Reject.TOO_SMALL, PhotoRules.shape(region(384, 256, length = 3_000), Source.THUMBNAILS))
        assertEquals("centre-cropped squares cannot be compared with the original", Reject.WRONG_SHAPE, PhotoRules.shape(region(300, 300), Source.THUMBNAILS))
    }

    @Test fun webpInChatsAndHiddenFoldersIsASticker() {
        assertEquals(Reject.APP_ASSET, PhotoRules.shape(region(512, 512, format = "webp"), Source.WHATSAPP))
        assertEquals(Reject.APP_ASSET, PhotoRules.shape(region(512, 512, format = "webp"), Source.HIDDEN))
        assertNull(PhotoRules.shape(region(1200, 800, format = "webp"), Source.TRASH))
    }

    @Test fun hiddenAndCarvedMustLookLikeUserPhotos() {
        assertTrue(PhotoRules.needsPhotoProof(Source.HIDDEN))
        assertTrue(PhotoRules.needsPhotoProof(Source.CARVED))
        assertFalse(PhotoRules.needsPhotoProof(Source.TRASH))
        assertFalse(PhotoRules.needsPhotoProof(Source.WHATSAPP))
        assertTrue(PhotoRules.looksLikeUserPhoto(region(720, 480), cameraTags = true))
        assertFalse("720x480 without camera tags is not enough", PhotoRules.looksLikeUserPhoto(region(720, 480), cameraTags = false))
        assertFalse("a 1024x1024 game texture is not enough either", PhotoRules.looksLikeUserPhoto(region(1024, 1024), cameraTags = false))
        assertTrue(PhotoRules.looksLikeUserPhoto(region(1600, 1066), cameraTags = false))
        assertFalse(PhotoRules.looksLikeUserPhoto(region(4000, 600), cameraTags = false))
    }

    @Test fun appFoldersAndFilesAreRecognised() {
        for (d in listOf("com.facebook.katana", "org.telegram.messenger", ".cache", "image_cache", ".facebook_cache", "emoji", "stickers", "app_icons", "glide", "assets", ".IMGCache".lowercase()))
            assertTrue("$d is an app folder", AppAssets.isAssetDir(d))
        for (d in listOf("dcim", "camera", "pictures", ".vault", "download", "export", "holiday 2025", "v1.2.3", "my.photos.2020", "screenshots", ".thumbnails"))
            assertFalse("$d is a user folder", AppAssets.isAssetDir(d))
        for (f in listOf("ic_launcher.png", "icon_12.png", "sticker_9.webp", "emoji-smile.png", "avatar.jpg", "splash.jpg", "logo.png"))
            assertTrue("$f is an asset", AppAssets.isAssetFile(f))
        for (f in listOf("img_2025.jpg", "dsc0001.jpg", "photo.jpg", "iconic_view.jpg", "holiday.jpg"))
            assertFalse("$f is a photo", AppAssets.isAssetFile(f))
    }
}
