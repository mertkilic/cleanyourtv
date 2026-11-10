package com.gathin.tvhome.hero

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the carousel shows: the feed (cached first, then refreshed when stale) and each slide's colour. */
class HeroController(
    private val context: Context,
    private val repo: HeroRepository,
    val imageLoader: ImageLoader,
) {
    var slides by mutableStateOf<List<Slide>>(emptyList())
        private set
    var source by mutableStateOf(SOURCE_TMDB)
        private set

    /** Slide key to ARGB, from Palette over a small copy of its backdrop: the glow and the kicker's bar. */
    val accents = mutableStateMapOf<String, Int>()

    /** Slide key to ARGB: the backdrop's average colour, darkened; what the artwork fades into under the dock. */
    val bases = mutableStateMapOf<String, Int>()

    /** fetchedAtMs of the feed on screen, so a feed the worker saved meanwhile replaces it on the next visit. */
    private var shownAt = -1L

    /**
     * The cached feed (a small file, read on every visit to Home, so a feed the warm-cache worker fetched meanwhile
     * shows), then a fetch only if that feed is from another day.
     */
    suspend fun refreshIfStale() {
        withContext(Dispatchers.IO) { repo.cached() }?.let { if (it.fetchedAtMs != shownAt) show(it) }
        repo.refreshIfDue()?.let { show(it) }
    }

    private suspend fun show(feed: Feed) {
        shownAt = feed.fetchedAtMs
        slides = feed.slides
        source = feed.source
        for (s in feed.slides) {
            if (s.key in accents) continue
            colours(s)?.let { (accent, base) ->
                accents[s.key] = accent
                bases[s.key] = base
            }
        }
    }

    private suspend fun colours(slide: Slide): Pair<Int, Int>? {
        val request = ImageRequest.Builder(context)
            .data(HeroFeed.imageUrl(slide.backdropPath, "w300"))
            .size(96, 54)
            .allowHardware(false)   // Palette reads the pixels
            .build()
        val bitmap = ((imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
        return withContext(Dispatchers.Default) { (vivid(bitmap) ?: HeroUi.DEFAULT_ACCENT) to base(bitmap) }
    }

    /** The mean of every pixel, kept dark (value 0.14 to 0.26) so white text and the app icons read on it. */
    private fun base(bitmap: Bitmap): Int {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in px) {
            r += Color.red(p); g += Color.green(p); b += Color.blue(p)
        }
        val n = px.size.coerceAtLeast(1)
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceAtMost(0.65f)
        hsv[2] = hsv[2].coerceIn(0.14f, 0.26f)
        return Color.HSVToColor(hsv)
    }

    private fun vivid(bitmap: Bitmap): Int? {
        val p = Palette.from(bitmap).maximumColorCount(12).generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: return null
        // Bright and saturated enough to read as a glow over a dark Home, never neon.
        val hsv = FloatArray(3)
        Color.colorToHSV(swatch.rgb, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.90f)
        return Color.HSVToColor(hsv)
    }
}
