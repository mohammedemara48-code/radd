package app.radd.engine

import app.radd.model.Source
import kotlin.math.max
import kotlin.math.min

/** Folder and file names that mean "this belongs to an app, not to the user". Names are lower-cased by the caller. */
object AppAssets {
    private val ASSET_WORDS = setOf(
        "emoji", "emojis", "emoticons", "sticker", "stickers", "icon", "icons", "avatar", "avatars", "asset", "assets", "glide", "fresco",
        "picasso", "webview", "okhttp", "volley", "unity", "unreal", "sdk", "ads", "splash", "wallpapers", "resources", "drawable", "drawables",
        "sprites", "gifs",
    )
    private val FILE_WORDS = setOf("icon", "icons", "ic", "launcher", "sticker", "stickers", "emoji", "avatar", "sprite", "splash", "logo", "banner", "thumb_icon")
    private val PACKAGE = Regex("^(com|org|net|io|app|me|co|tv|de|ru|cn|jp|kr|in|ir|eg|sa|ae|ai|dev|gg|us|uk|fr|br)\\.[a-z0-9_]+(\\.[a-z0-9_]+)+$")
    private val SPLIT = Regex("[^a-z0-9]+")

    fun isAssetDir(name: String): Boolean {
        if (PACKAGE.matches(name)) return true
        val tokens = name.split(SPLIT).filter { it.isNotEmpty() }
        return tokens.any { it in ASSET_WORDS || it.contains("cache") || it == "imgcache" }
    }

    fun isAssetFile(name: String): Boolean = name.split(SPLIT).any { it in FILE_WORDS }
}

/** Why a picture was left out. Only used for the counters in the scan summary. */
enum class Reject { TOO_SMALL, WRONG_SHAPE, APP_ASSET, NOT_A_USER_PHOTO }

/** What makes a picture worth listing as "a photo the user deleted". Everything here is pure and unit-tested. */
object PhotoRules {
    const val MIN_BYTES = 10 * 1024L
    const val MIN_THUMB_BYTES = 4 * 1024L
    const val MIN_SIDE = 200
    const val MAX_ASPECT = 4.0
    const val BIG_SIDE = 1000
    const val BIG_PIXELS = 1_500_000L

    /** Size, dimension and shape checks that need nothing but the region header. Null means "passes". */
    fun shape(r: Region, source: Source): Reject? {
        val thumb = source == Source.THUMBNAILS
        if (r.length < (if (thumb) MIN_THUMB_BYTES else MIN_BYTES)) return Reject.TOO_SMALL
        val longSide = max(r.width, r.height)
        val shortSide = min(r.width, r.height)
        if (shortSide <= 0) return Reject.TOO_SMALL
        if (if (thumb) longSide < MIN_SIDE else shortSide < MIN_SIDE) return Reject.TOO_SMALL
        val aspect = longSide.toDouble() / shortSide
        if (aspect > MAX_ASPECT) return Reject.WRONG_SHAPE
        // system thumbnails are often centre-cropped squares; they cannot be compared with the original reliably
        if (thumb && aspect < 1.04) return Reject.WRONG_SHAPE
        // WebP in a chat or a hidden folder is, in practice, a sticker
        if (r.format == "webp" && source != Source.TRASH && source != Source.LOST_DIR) return Reject.APP_ASSET
        return null
    }

    /** Camera EXIF, or a size only real photos and screenshots reach. */
    fun looksLikeUserPhoto(r: Region, cameraTags: Boolean): Boolean {
        if (cameraTags) return true
        val shortSide = min(r.width, r.height)
        return shortSide >= BIG_SIDE && r.width.toLong() * r.height >= BIG_PIXELS
    }

    /** Sources whose files are not whole, named photos and so must prove they are a user's photo. */
    fun needsPhotoProof(source: Source) = source == Source.HIDDEN || source == Source.CARVED
}
