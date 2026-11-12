package com.gathin.tvhome.hero

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.gathin.tvhome.R
import java.util.Locale
import kotlinx.coroutines.delay

internal object HeroUi {
    const val BACKDROP_SIZE = "w1280"
    const val LOGO_SIZE = "w500"
    const val DEFAULT_ACCENT = 0xFF8A9BB4.toInt()
    const val FADE_MS = 700
    /** The text block lines up with the launcher's own top shelf (activity_main.xml, hero: 68dp). */
    val START = 68.dp
    /** Below the clock and the date (activity_main.xml's header block). */
    val HEADER_ROOM = 112.dp
    val CHIP_HEIGHT = 40.dp
    /** Kicker, meta line, two lines of overview and the gaps: everything in the text block but the title. */
    val TEXT_FIXED = 104.dp
    /** The open card: the dock's distance below the actions. */
    val DOCK_GAP = 48.dp
    /** The open card: the overview's most lines, the motion. */
    const val OPEN_LINES = 8
    const val OPEN_MS = 420
    const val CLOSE_MS = 440
    /** A minute with no key closes the open card. */
    const val IDLE_CLOSE_MS = 60_000L
}

private val Sans = FontFamily.SansSerif

/** What the open card offers, left to right. */
private enum class HeroAction { TRAILER, SEARCH, WATCHED }

private fun actionsFor(slide: Slide, settings: HeroBridge.Settings): List<HeroAction> = buildList {
    if (slide.trailerKey != null) add(HeroAction.TRAILER)
    if (settings.targetLabel != null) add(HeroAction.SEARCH)
    add(HeroAction.WATCHED)
}

/**
 * The carousel: a full-width backdrop above the dock that changes every few seconds, with the title (its logo
 * when TMDB has one), why it is here (people watching now), and a hint of what OK does.
 *
 * Unfocused it is ambient art and advances on its own. Focused (Up from the dock) it stops: Left and Right page,
 * OK opens the card, holding OK marks the title as watched, Down or Back return to the dock, Up goes to the top bar.
 *
 * Open, the card stays where it is (kicker, title, artwork): the dock and the grid slide off the bottom, the dots
 * give way, and in the space they leave the overview runs on in full, with the runtime and the actions (trailer,
 * search, watched) under it. Left and Right move between the actions, OK presses one, Down or Back close the card,
 * and so does a minute with no key. A title marked as watched leaves the carousel when the card closes.
 */
@Composable
internal fun HeroPager(bridge: HeroBridge) {
    val slides = bridge.controller.slides
    if (slides.isEmpty()) return
    val settings = bridge.settings
    val loader = bridge.controller.imageLoader
    val context = LocalContext.current
    val density = LocalDensity.current
    val bg = Color(settings.background)

    var index by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    // Whether the OK press in progress was a long press (collapsed: it marked the title as watched).
    var longPressed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var action by remember { mutableIntStateOf(0) }
    // The title the card was opened on: only it shows the actions, so a title that takes the place of one marked as
    // watched comes in as a closed card while the open one is still closing.
    var openKey by remember { mutableStateOf<String?>(null) }
    // Bottom of the text block (actions included) in the window, px: the dock moves down to stay below it.
    var blockBottom by remember { mutableFloatStateOf(0f) }
    // Uptime of the last key while the card is open, for closing it after a minute with no key.
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    val n = slides.size
    val current = slides[index.mod(n)]
    val progress = remember { Animatable(0f) }
    val actions = actionsFor(current, settings)

    fun setExpanded(open: Boolean) {
        if (expanded == open) return
        expanded = open
        action = 0
        if (open) openKey = current.key
        bridge.expanded(open)
    }

    fun press(a: HeroAction) = when (a) {
        HeroAction.TRAILER -> bridge.playTrailer(current)
        HeroAction.SEARCH -> bridge.select(current)
        HeroAction.WATCHED -> bridge.toggleWatched(current)
    }

    // Advance only while Home is in front and the carousel is not being browsed. The frame clock already stops when
    // Home is stopped; the lifecycle check also covers the reduced-motion path, which waits with a plain delay.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(current.key, focused, running, n) {
        if (focused) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        if (!running || n < 2) return@LaunchedEffect
        if (settings.motion) progress.animateTo(1f, tween(settings.dwellMs.toInt(), easing = LinearEasing))
        else delay(settings.dwellMs)
        forward = true
        index = (index + 1).mod(n)
    }

    LaunchedEffect(expanded, lastKeyAt) {
        if (!expanded) return@LaunchedEffect
        delay(HeroUi.IDLE_CLOSE_MS)
        setExpanded(false)
    }

    // The next slide's images, so a change never shows an empty frame.
    LaunchedEffect(current.key) {
        val next = slides[(index + 1).mod(n)]
        loader.enqueue(ImageRequest.Builder(context).data(HeroFeed.imageUrl(next.backdropPath, HeroUi.BACKDROP_SIZE)).build())
        next.logoPath?.let { loader.enqueue(ImageRequest.Builder(context).data(HeroFeed.imageUrl(it, HeroUi.LOGO_SIZE)).build()) }
    }

    val accentArgb = bridge.controller.accents[current.key] ?: HeroUi.DEFAULT_ACCENT
    val accent by animateColorAsState(Color(accentArgb), tween(900), label = "accent")
    // The colour the artwork fades into under the dock and that fills the rest of Home: the slide's darkened average.
    val base by animateColorAsState(
        bridge.controller.bases[current.key]?.let { Color(it) } ?: lerp(bg, Color.Black, 0.3f),
        tween(HeroUi.FADE_MS + 300),
        label = "base",
    )
    LaunchedEffect(current.key, focused, accentArgb) {
        if (focused) bridge.host.heroAccent(accentArgb)
    }

    // 0 closed, 1 open: one value drives the whole change, each part over its own stretch of it.
    val open by animateFloatAsState(
        if (expanded) 1f else 0f,
        when {
            settings.motion -> tween(if (expanded) HeroUi.OPEN_MS else HeroUi.CLOSE_MS, easing = FastOutSlowInEasing)
            settings.animations -> tween(150)
            else -> snap()
        },
        label = "open",
    )

    // Open, the dock and the grid move down just far enough to stay DOCK_GAP below the actions, following the
    // overview as it unrolls; a short overview barely moves them. Closed, they are where they always are.
    val dockGapPx = with(density) { HeroUi.DOCK_GAP.toPx() }
    LaunchedEffect(Unit) {
        snapshotFlow {
            val dockTop = bridge.dockTopPx
            if (open == 0f || dockTop <= 0) 0f else (blockBottom + dockGapPx - dockTop).coerceAtLeast(0f)
        }.distinctUntilChanged().collect { bridge.host.heroDockShift(it) }
    }
    // Closed for good: the actions belong to no title any more.
    if (!expanded && open == 0f && openKey != null) SideEffect { openKey = null }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(bridge.focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) setExpanded(false)
            }
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                val up = e.type == KeyEventType.KeyUp
                val ne = e.nativeKeyEvent
                if (expanded && down) lastKeyAt = ne.eventTime
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (down) {
                            if (expanded) action = (action - 1).coerceAtLeast(0)
                            else { forward = false; index = (index - 1).mod(n) }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) {
                            if (expanded) action = (action + 1).coerceAtMost(actions.lastIndex)
                            else { forward = true; index = (index + 1).mod(n) }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        // Closed: a short press opens the card; held, the first key repeat marks the title as watched.
                        // Open: a short press presses the focused action; held, nothing. Either way the release of a
                        // held press does nothing.
                        when {
                            down && ne.repeatCount == 0 -> longPressed = false
                            down && !longPressed -> {
                                longPressed = true
                                if (!expanded) bridge.markWatched(current)
                            }
                            up && !longPressed ->
                                if (expanded) actions.getOrNull(action)?.let { press(it) } else setExpanded(true)
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) { if (expanded) setExpanded(false) else bridge.host.heroExitDown() }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        if (up) { if (expanded) setExpanded(false) else bridge.host.heroExitDown() }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        // Open, Up stays on the card: the top bar is a press of Down (close) and Up away.
                        if (down && !expanded) bridge.host.heroExitUp()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        val heightPx = with(density) { maxHeight.toPx() }
        val dockTopPx = bridge.dockTopPx.takeIf { it > 0 }?.toFloat() ?: (heightPx * 0.66f)
        val dockTop = with(density) { dockTopPx.toDp() }
        // The artwork runs on under the dock's icons and fades into the base colour there (open or not).
        val artPx = with(density) { (dockTop + 150.dp).toPx() }
        val fadeFrom = artPx * 0.50f
        val fadeTo = artPx * 0.98f

        Box(
            Modifier
                .fillMaxSize()
                .background(base)
        )
        // The chip row ends just above the dock, where the launcher's own app name would sit.
        val chipBottom = (maxHeight - dockTop + 22.dp).coerceAtLeast(24.dp)
        // The text block fills what is left between the header (clock, pills) and the chip row; only the title (logo)
        // height gives, so on a TV that runs at 2x (BRAVIA, 960x540 dp) it never climbs under the clock. Opening the
        // card moves none of it: the block grows downwards only (SlideText).
        val textBottom = chipBottom + HeroUi.CHIP_HEIGHT + 16.dp
        val titleHeight = (maxHeight - textBottom - HeroUi.HEADER_ROOM - HeroUi.TEXT_FIXED).coerceIn(40.dp, 96.dp)
        // The actions come in late and go out early, rising a little as they come in.
        val actionsAlpha = ((open - 0.5f) * 2f).coerceIn(0f, 1f)
        val watched = current.key in bridge.controller.watchedKeys

        AnimatedContent(
            targetState = current,
            contentKey = { it.key },
            transitionSpec = {
                if (settings.animations) fadeIn(tween(HeroUi.FADE_MS)) togetherWith fadeOut(tween(HeroUi.FADE_MS))
                else EnterTransition.None togetherWith ExitTransition.None
            },
            label = "slide",
        ) { slide ->
            Box(Modifier.fillMaxSize()) {
                Backdrop(slide, { fadeFrom }, { fadeTo }, base, settings, loader)
                SlideText(
                    slide = slide,
                    source = bridge.controller.source,
                    accent = accent,
                    forward = forward,
                    motion = settings.motion,
                    expanded = expanded,
                    settled = open == 0f,
                    loader = loader,
                    titleHeight = titleHeight,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = HeroUi.START, bottom = textBottom),
                    onBottom = if (slide.key == (openKey ?: current.key)) { y -> blockBottom = y } else null,
                ) {
                    if (open > 0f && slide.key == openKey) Column(
                        // Takes its room as the card opens (and gives it back as it closes), so the dock below
                        // moves smoothly instead of jumping by the actions' height.
                        Modifier.layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            layout(p.width, (p.height * open).roundToInt()) { p.place(0, 0) }
                        }
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Row(
                            Modifier.graphicsLayer {
                                alpha = actionsAlpha
                                translationY = (1f - actionsAlpha) * 8.dp.toPx()
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            actions.forEachIndexed { i, a ->
                                val selected = expanded && i == action
                                when (a) {
                                    HeroAction.TRAILER -> ActionButton(stringResource(R.string.hero_watch_trailer), Icon.PLAY, selected)
                                    HeroAction.SEARCH -> ActionButton(
                                        stringResource(R.string.hero_search_in, settings.targetLabel.orEmpty()), Icon.SEARCH, selected,
                                    )
                                    HeroAction.WATCHED -> ActionButton(
                                        stringResource(if (watched) R.string.hero_watched else R.string.hero_mark_watched),
                                        if (watched) Icon.CHECK else Icon.PLUS,
                                        selected,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Where you are in the rotation; it gives way first when the card opens and comes back last when it closes.
        val dotsAlpha = (1f - open * 3f).coerceIn(0f, 1f)
        if (dotsAlpha > 0f) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = HeroUi.START, bottom = chipBottom)
                    .height(HeroUi.CHIP_HEIGHT)
                    .graphicsLayer { alpha = dotsAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Indicators(n, index.mod(n), progress.value)
            }
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.Backdrop(
    slide: Slide,
    fadeFrom: () -> Float,
    fadeTo: () -> Float,
    base: Color,
    settings: HeroBridge.Settings,
    loader: coil.ImageLoader,
) {
    // Ken Burns: a slow push-in towards the right, where the subject of a backdrop usually is (the text is left).
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (settings.motion) zoom.animateTo(1.07f, tween((settings.dwellMs + HeroUi.FADE_MS * 2).toInt(), easing = LinearEasing))
    }
    val tint = base
    Box(
        Modifier
            .fillMaxSize()
            // The whole backdrop (image and scrims) fades out to transparent between fadeFrom and fadeTo (px from the
            // top: from above the dock to below its icons, or lower with the card open), over the full-screen base
            // colour: the artwork turns into the slide's own colour with no seam. Read at draw time, so the card
            // opening redraws the mask without a new layout.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = fadeFrom(), endY = fadeTo()),
                    blendMode = BlendMode.DstIn,
                )
            }
    ) {
        AsyncImage(
            model = HeroFeed.imageUrl(slide.backdropPath, HeroUi.BACKDROP_SIZE),
            contentDescription = null,
            imageLoader = loader,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom.value
                    scaleY = zoom.value
                    transformOrigin = TransformOrigin(0.72f, 0.35f)
                },
        )
        // The header (clock, pills) stays readable over a bright sky.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.28f to Color.Transparent))
        )
        // The text side, tinted with the slide's colour so Home takes on its mood.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to tint.copy(alpha = 0.94f),
                        0.38f to tint.copy(alpha = 0.62f),
                        0.70f to Color.Transparent,
                    )
                )
        )
    }
}

@Composable
private fun AnimatedVisibilityScope.SlideText(
    slide: Slide,
    source: String,
    accent: Color,
    forward: Boolean,
    motion: Boolean,
    expanded: Boolean,
    settled: Boolean,
    loader: coil.ImageLoader,
    titleHeight: Dp,
    modifier: Modifier,
    onBottom: ((Float) -> Unit)?,
    footer: @Composable ColumnScope.() -> Unit,
) {
    // The block's height when the card is closed. While the card opens, stays open and closes, the block reports
    // that height to the layout and draws the rest below it, so the kicker and the title stay put and only the
    // overview and the actions run on downwards, into the space the dock leaves.
    val closedHeight = remember(slide.key) { IntArray(1) }
    val enter = if (motion) {
        slideInHorizontally(tween(560, delayMillis = 140)) { w -> (if (forward) w else -w) / 14 } +
            fadeIn(tween(480, delayMillis = 160))
    } else EnterTransition.None
    Column(
        modifier
            .widthIn(max = 600.dp)
            .animateEnterExit(enter = enter, exit = if (motion) fadeOut(tween(220)) else ExitTransition.None)
            .layout { measurable, constraints ->
                // No height limit: open, the block runs on below the space it was given (where the dock was).
                val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                if (settled || closedHeight[0] == 0) closedHeight[0] = p.height
                layout(p.width, closedHeight[0]) { p.place(0, 0) }
            }
            // After layout(): the whole block, the part drawn below the reported height included.
            .onGloballyPositioned { c -> onBottom?.invoke(c.positionInRoot().y + c.size.height) }
    ) {
        Kicker(slide, source, accent)
        Spacer(Modifier.height(12.dp))
        Title(slide, loader, titleHeight)
        Spacer(Modifier.height(12.dp))
        val meta = listOfNotNull(
            slide.year.ifEmpty { null },
            slide.rating.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) },
            slide.genres.joinToString(", ").ifEmpty { null },
            slide.seasons?.let { pluralStringResource(R.plurals.hero_season_count, it, it) },
            slide.runtime?.takeIf { expanded }?.let {
                if (it >= 60) stringResource(R.string.hero_runtime_hm, it / 60, it % 60) else stringResource(R.string.hero_runtime_m, it)
            },
        ).joinToString("   ·   ")
        if (meta.isNotEmpty()) {
            BasicText(meta, maxLines = 1, style = TextStyle(color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontFamily = Sans))
            Spacer(Modifier.height(6.dp))
        }
        if (slide.overview.isNotEmpty()) {
            BasicText(
                slide.overview,
                maxLines = if (expanded) HeroUi.OPEN_LINES else 2,
                overflow = TextOverflow.Ellipsis,
                // The extra lines unroll (and roll back up) instead of jumping in.
                modifier = Modifier.animateContentSize(if (motion) tween(300, easing = FastOutSlowInEasing) else snap()),
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, lineHeight = 20.sp,
                    fontFamily = Sans, fontWeight = FontWeight.Light,
                ),
            )
        }
        footer()
    }
}

/** "12.4K WATCHING NOW · SERIES", after a short bar in the slide's colour (the launcher's race panel style). */
@Composable
private fun Kicker(slide: Slide, source: String, accent: Color) {
    val why = slide.watchers?.takeIf { source == SOURCE_TRAKT && it > 0 }
        ?.let { stringResource(R.string.hero_watching_now, HeroFeed.compactCount(it)) }
        ?: stringResource(R.string.hero_trending_week)
    val kind = stringResource(if (slide.isSeries) R.string.hero_series else R.string.hero_movie)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(13.dp)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        BasicText(
            "$why  ·  $kind".uppercase(Locale.getDefault()),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.74f), fontSize = 12.sp, fontFamily = Sans,
                fontWeight = FontWeight.Medium, letterSpacing = 0.22.em,
            ),
        )
    }
}

/** The title's logo when TMDB has one, else the title in the launcher's large light type. */
@Composable
private fun Title(slide: Slide, loader: coil.ImageLoader, height: Dp) {
    var logoFailed by remember(slide.key) { mutableStateOf(slide.logoPath == null) }
    if (!logoFailed) {
        AsyncImage(
            model = HeroFeed.imageUrl(slide.logoPath!!, HeroUi.LOGO_SIZE),
            contentDescription = slide.title,
            imageLoader = loader,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { logoFailed = true },
            modifier = Modifier
                .width(height * 5)
                .height(height),
        )
    } else {
        BasicText(
            slide.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White, fontSize = (height.value * 0.62f).sp, fontFamily = Sans,
                fontWeight = FontWeight.Light, letterSpacing = (-0.02).em,
            ),
        )
    }
}

private enum class Icon { PLAY, SEARCH, PLUS, CHECK }

/** One of the open card's actions: white when it has focus (as the launcher's pills look focused), glass otherwise. */
@Composable
private fun ActionButton(label: String, icon: Icon, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "actionScale")
    val fill = if (focused) Color.White else Color.White.copy(alpha = 0.16f)
    val ink = if (focused) Color(0xFF111111) else Color.White.copy(alpha = 0.92f)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 0.5f) }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                Icon.PLAY -> drawPath(
                    Path().apply { moveTo(w * 0.12f, 0f); lineTo(w, h / 2f); lineTo(w * 0.12f, h); close() },
                    ink,
                )
                Icon.SEARCH -> {
                    drawCircle(ink, radius = w * 0.34f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
                    drawLine(ink, Offset(w * 0.68f, h * 0.68f), Offset(w * 0.96f, h * 0.96f), stroke.width, StrokeCap.Round)
                }
                Icon.PLUS -> {
                    drawLine(ink, Offset(w / 2f, h * 0.08f), Offset(w / 2f, h * 0.92f), stroke.width, StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.08f, h / 2f), Offset(w * 0.92f, h / 2f), stroke.width, StrokeCap.Round)
                }
                Icon.CHECK -> drawPath(
                    Path().apply { moveTo(w * 0.06f, h * 0.54f); lineTo(w * 0.38f, h * 0.84f); lineTo(w * 0.96f, h * 0.18f) },
                    ink,
                    style = stroke,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        BasicText(label, style = TextStyle(color = ink, fontSize = 15.sp, fontFamily = Sans, fontWeight = FontWeight.Medium))
    }
}

/** A dot per slide; the current one is a pill that fills over its dwell (and stays full while paused). */
@Composable
private fun Indicators(count: Int, current: Int, progress: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val width by animateDpAsState(if (i == current) 28.dp else 6.dp, tween(300), label = "dot")
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == current) 0.28f else 0.35f))
            ) {
                if (i == current) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}
