package com.gathin.tvhome.hero

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
}

private val Sans = FontFamily.SansSerif

/**
 * The carousel: a full-width backdrop above the dock that changes every few seconds, with the title (its logo
 * when TMDB has one), why it is here (people watching now), and the one thing OK does.
 *
 * Unfocused it is ambient art and advances on its own. Focused (Up from the dock) it stops, Left and Right page,
 * OK searches the title in TiviMate, Down or Back return to the dock, Up goes to the top bar.
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
    val n = slides.size
    val current = slides[index.mod(n)]
    val progress = remember { Animatable(0f) }

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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(bridge.focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (down) { forward = false; index = (index - 1).mod(n) }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) { forward = true; index = (index + 1).mod(n) }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        if (e.type == KeyEventType.KeyUp) bridge.select(current)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) bridge.host.heroExitDown()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
                        if (e.type == KeyEventType.KeyUp) bridge.host.heroExitDown()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (down) bridge.host.heroExitUp()
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
        // The artwork runs on under the dock's icons and fades into the base colour there.
        val artHeight = dockTop + 150.dp

        Box(
            Modifier
                .fillMaxSize()
                .background(base)
        )
        // The chip row ends just above the dock, where the launcher's own app name would sit.
        val chipBottom = (maxHeight - dockTop + 22.dp).coerceAtLeast(24.dp)
        // The text block fills what is left between the header (clock, pills) and the chip row; only the title (logo)
        // height gives, so on a TV that runs at 2x (BRAVIA, 960x540 dp) it never climbs under the clock.
        val textBottom = chipBottom + HeroUi.CHIP_HEIGHT + 16.dp
        val titleHeight = (maxHeight - textBottom - HeroUi.HEADER_ROOM - HeroUi.TEXT_FIXED).coerceIn(40.dp, 96.dp)

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
                Backdrop(slide, artHeight, base, settings, loader)
                SlideText(
                    slide = slide,
                    source = bridge.controller.source,
                    accent = accent,
                    forward = forward,
                    motion = settings.motion,
                    loader = loader,
                    titleHeight = titleHeight,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = HeroUi.START, bottom = textBottom),
                )
            }
        }

        // Fixed across slides: what OK does, where you are in the rotation, and the data credit.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = HeroUi.START, end = 68.dp, bottom = chipBottom),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchChip(settings.targetLabel, focused)
            Spacer(Modifier.width(28.dp))
            Indicators(n, index.mod(n), progress.value)
            Spacer(Modifier.weight(1f))
            BasicText(
                stringResource(if (bridge.controller.source == SOURCE_TRAKT) R.string.hero_attribution else R.string.hero_attribution_tmdb),
                style = TextStyle(color = Color.White.copy(alpha = 0.38f), fontSize = 11.sp, fontFamily = Sans, letterSpacing = 0.06.em),
            )
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.Backdrop(
    slide: Slide,
    artHeight: androidx.compose.ui.unit.Dp,
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
            .fillMaxWidth()
            .height(artHeight)
            // The whole backdrop (image and scrims) fades out to transparent from above the dock to below its icons,
            // over the full-screen base colour: the artwork turns into the slide's own colour with no seam.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0.50f to Color.Black, 0.98f to Color.Transparent),
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
    loader: coil.ImageLoader,
    titleHeight: Dp,
    modifier: Modifier,
) {
    val enter = if (motion) {
        slideInHorizontally(tween(560, delayMillis = 140)) { w -> (if (forward) w else -w) / 14 } +
            fadeIn(tween(480, delayMillis = 160))
    } else EnterTransition.None
    Column(
        modifier
            .widthIn(max = 600.dp)
            .animateEnterExit(enter = enter, exit = if (motion) fadeOut(tween(220)) else ExitTransition.None)
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
        ).joinToString("   ·   ")
        if (meta.isNotEmpty()) {
            BasicText(meta, maxLines = 1, style = TextStyle(color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontFamily = Sans))
            Spacer(Modifier.height(6.dp))
        }
        if (slide.overview.isNotEmpty()) {
            BasicText(
                slide.overview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, lineHeight = 20.sp,
                    fontFamily = Sans, fontWeight = FontWeight.Light,
                ),
            )
        }
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

/** The one action: white when the carousel has focus (as the launcher's pills look focused), glass otherwise. */
@Composable
private fun SearchChip(targetLabel: String?, focused: Boolean) {
    val label = targetLabel?.let { stringResource(R.string.hero_search_in, it) }
        ?: stringResource(R.string.hero_not_installed, "TiviMate")
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "chipScale")
    val fill = if (focused) Color.White else Color.White.copy(alpha = 0.16f)
    val ink = if (focused) Color(0xFF111111) else Color.White.copy(alpha = 0.92f)
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f) }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp, 12.dp)) {
            val p = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(p, ink)
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
