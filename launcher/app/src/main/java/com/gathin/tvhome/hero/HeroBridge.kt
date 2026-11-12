package com.gathin.tvhome.hero

import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gathin.tvhome.R
import kotlinx.coroutines.launch

/**
 * MainActivity's handle on the hero carousel (config.xml hero_pager). Java keeps the dock, the grid and the top
 * bar; this owns the ComposeView behind them. Focus moves in from the dock (Up) or the top bar (Down), and out
 * through [Host].
 */
class HeroBridge private constructor(
    private val activity: ComponentActivity,
    private val view: ComposeView,
    internal val host: Host,
    internal val settings: Settings,
    internal val controller: HeroController,
) {
    /** What Home does when focus leaves the carousel, and with its colour. */
    interface Host {
        /** Down or Back: focus goes back to the dock. */
        fun heroExitDown()

        /** Up: focus goes to the top bar. */
        fun heroExitUp()

        /** The slide on screen while the carousel has focus, for the ambient glow. */
        fun heroAccent(color: Int)

        /** The carousel appeared (it has slides) or went away. */
        fun heroShown(shown: Boolean)

        /** How far down (px) the dock and the grid move to make room for the open card; 0 when it is closed. */
        fun heroDockShift(px: Float)
    }

    internal data class Settings(
        val dwellMs: Long,
        val language: String,
        val target: String,
        val targetLabel: String?,
        val background: Int,
        val motion: Boolean,
        val animations: Boolean,
    )

    internal val focusRequester = FocusRequester()

    /** Top of the dock in the window, from MainActivity's grid layout; 0 until it is known. */
    internal var dockTopPx by mutableIntStateOf(0)
        private set

    fun setDockTop(px: Int) {
        if (px > 0 && px != dockTopPx) dockTopPx = px
    }

    fun hasSlides(): Boolean = controller.slides.isNotEmpty() && view.visibility == View.VISIBLE

    fun hasFocus(): Boolean = view.hasFocus()

    /** Focus into the carousel; false when it has nothing to show. */
    fun requestFocus(): Boolean {
        if (!hasSlides()) return false
        return try {
            focusRequester.requestFocus()
            true
        } catch (e: IllegalStateException) {
            view.requestFocus()
        }
    }

    /** Memory pressure while Home is in the background: drop decoded images (the disk cache stays). */
    fun trimMemory() {
        controller.imageLoader.memoryCache?.clear()
    }

    internal fun select(slide: Slide) {
        activity.lifecycleScope.launch { TiviMateHandoff.search(activity, settings.target, slide.title) }
    }

    /** The card opened or closed. Closing takes the titles marked as watched out of the carousel. */
    internal fun expanded(open: Boolean) {
        if (open) return
        activity.lifecycleScope.launch {
            controller.applyWatched()
            if (controller.slides.isEmpty() && view.hasFocus()) host.heroExitDown()
        }
    }

    internal fun toggleWatched(slide: Slide) {
        activity.lifecycleScope.launch { controller.toggleWatched(slide) }
    }

    internal fun playTrailer(slide: Slide) {
        slide.trailerKey?.let { TrailerHandoff.open(activity, it) }
    }

    /** Long press of OK: the title leaves the carousel for good, and the next one of the day's feed comes in. */
    internal fun markWatched(slide: Slide) {
        activity.lifecycleScope.launch {
            controller.markWatched(slide)
            Toast.makeText(activity, activity.getString(R.string.hero_marked_watched, slide.title), Toast.LENGTH_SHORT).show()
            // The last title gone: the carousel hides, so focus goes back to the dock before it does.
            if (controller.slides.isEmpty() && view.hasFocus()) host.heroExitDown()
        }
    }

    private fun start() {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { HeroPager(this) }
        activity.lifecycleScope.launch {
            snapshotFlow { controller.slides.isNotEmpty() }.collect { shown ->
                view.visibility = if (shown) View.VISIBLE else View.GONE
                host.heroShown(shown)
            }
        }
        // Each time Home is on screen: the cached feed, then a fetch only if that feed is from another day.
        // Cancelled when Home goes to the background, so nothing runs behind another app.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.refreshIfStale() }
        }
        WarmCacheWorker.schedule(activity, settings.language)
    }

    companion object {
        /** Null when hero_pager is off, there is no TMDB token in local.properties, or no view: Home is as before. */
        @JvmStatic
        fun attach(
            activity: ComponentActivity,
            view: ComposeView?,
            background: Int,
            animations: Boolean,
            reducedMotion: Boolean,
            host: Host,
        ): HeroBridge? {
            val res = activity.resources
            // A layout variant without the view (each layout-* copy of activity_main must have it) keeps Home as before.
            if (view == null || !res.getBoolean(R.bool.hero_pager) || !HeroNet.configured) return null
            val language = res.getString(R.string.hero_pager_language).trim().ifEmpty { "en-US" }
            val target = res.getString(R.string.hero_pager_target).trim()
            val settings = Settings(
                dwellMs = res.getInteger(R.integer.hero_pager_seconds).coerceIn(4, 60) * 1000L,
                language = language,
                target = target,
                targetLabel = TiviMateHandoff.label(activity, target),
                background = background,
                motion = animations && !reducedMotion,
                animations = animations,
            )
            val controller = HeroController(
                activity.applicationContext,
                HeroNet.repository(activity, language),
                HeroNet.imageLoader(activity),
                WatchedStore(activity),
            )
            return HeroBridge(activity, view, host, settings, controller).also { it.start() }
        }
    }
}
