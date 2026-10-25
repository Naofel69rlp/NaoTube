/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.desktop.player.PlaybackSession
import com.naotube.desktop.player.StreamPicker
import com.naotube.desktop.ui.BackButton
import com.naotube.desktop.ui.BackHandler
import com.naotube.desktop.ui.FocusSurface
import com.naotube.desktop.ui.LocalAccent
import com.naotube.desktop.ui.LocalNav
import com.naotube.desktop.ui.LocalWindowControls
import com.naotube.desktop.ui.MessagePanel
import com.naotube.desktop.ui.NaoColors
import com.naotube.desktop.ui.NaoIcon
import com.naotube.desktop.ui.NaoText
import com.naotube.desktop.ui.NaoType
import com.naotube.desktop.ui.PillButton
import com.naotube.desktop.ui.RoundIconButton
import com.naotube.desktop.ui.Screen
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.VideoItem
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class Panel { Quality, Subtitles, Speed }

@Composable
fun PlayerScreen(video: VideoItem, fromStart: Boolean, startMs: Long?) {
    val nav = LocalNav.current
    val window = LocalWindowControls.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val session = remember(video.url) {
        PlaybackSession(scope, video, if (fromStart) 0L else startMs) { next ->
            if (next != null) nav.push(Screen.Player(next)) else nav.back()
        }
    }
    DisposableEffect(session) {
        session.load()
        onDispose {
            session.release()
        }
    }
    // Leaving the player always leaves full screen.
    DisposableEffect(Unit) { onDispose { window.setFullscreen(false) } }
    LaunchedEffect(session) {
        while (true) {
            session.tick()
            delay(250)
        }
    }

    var controls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackAt by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }

    fun poke() {
        lastInteraction = System.currentTimeMillis()
        controls = true
    }
    fun flash(text: String) {
        feedback = text
        feedbackAt = System.currentTimeMillis()
    }

    LaunchedEffect(Unit) { delay(80); runCatching { rootFocus.requestFocus() } }

    // Hide the controls (and the mouse pointer) after 3 s without activity while playing.
    LaunchedEffect(controls, session.playing, panel, lastInteraction) {
        if (controls && session.playing && panel == null) {
            delay(3000)
            if (System.currentTimeMillis() - lastInteraction >= 2900) controls = false
        }
    }
    LaunchedEffect(feedbackAt) {
        if (feedbackAt > 0) {
            delay(900)
            feedback = null
        }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            window.isFullscreen() -> window.setFullscreen(false)
            else -> nav.back()
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        if (panel != null) {
            poke()
            return false
        }
        when (e.key) {
            Key.Spacebar, Key.K, Key.MediaPlayPause -> { session.togglePlay(); poke(); return true }
            Key.DirectionLeft, Key.J -> {
                if (e.key == Key.DirectionLeft && e.isAltPressed) return false
                session.seekBy(if (e.key == Key.J) -10_000 else -5_000)
                flash(if (e.key == Key.J) "−10 s" else "−5 s")
                poke()
                return true
            }
            Key.DirectionRight, Key.L -> {
                session.seekBy(if (e.key == Key.L) 10_000 else 5_000)
                flash(if (e.key == Key.L) "+10 s" else "+5 s")
                poke()
                return true
            }
            Key.DirectionUp -> { session.changeVolume(5); flash("Volume ${session.volume} %"); poke(); return true }
            Key.DirectionDown -> { session.changeVolume(-5); flash("Volume ${session.volume} %"); poke(); return true }
            Key.M -> { session.toggleMute(); flash(if (session.muted) "Son coupé" else "Son activé"); return true }
            Key.F -> { window.toggle(); return true }
            Key.MediaNext, Key.N -> {
                session.nextVideo()?.let { nav.push(Screen.Player(it)) }
                return true
            }
        }
        poke()
        return false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { handleKey(it) }
            .focusRequester(rootFocus)
            .focusable()
            .pointerHoverIcon(if (controls) PointerIcon.Default else HiddenPointer)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) poke()
                    }
                }
            }
    ) {
        // ------------------------------------------------ video
        VideoView(
            session,
            Modifier
                .fillMaxSize()
                .pointerInput(session) {
                    detectTapGestures(
                        onTap = {
                            if (panel != null) panel = null else session.togglePlay()
                            poke()
                        },
                        onDoubleTap = { window.toggle() },
                    )
                },
        )

        if ((session.loading || session.buffering) && session.error == null) {
            Spinner(Modifier.align(Alignment.Center))
        }

        val fb = feedback
        if (fb != null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { NaoText(fb, NaoType.Button.copy(fontSize = 14.sp)) }
        }

        val error = session.error
        if (error != null) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                val retry = remember { FocusRequester() }
                MessagePanel(
                    "Impossible de lire la vidéo",
                    error,
                    actionLabel = "Réessayer",
                    onAction = { session.load(force = true, keepPosition = session.position.takeIf { it > 0 }) },
                    focusRequester = retry,
                )
                LaunchedEffect(error) { delay(80); runCatching { retry.requestFocus() } }
            }
        }

        // ------------------------------------------------ SponsorBlock toast
        AnimatedVisibility(
            visible = session.skipToast != null,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 100.dp, end = 48.dp),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xDB121216))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                NaoIcon("ic_shield", NaoColors.SponsorGreen, 15.dp)
                Column {
                    NaoText("SPONSORBLOCK", NaoType.Overline.copy(fontSize = 8.sp, color = NaoColors.SponsorGreen))
                    NaoText(session.skipToast ?: "", NaoType.BodyStrong.copy(fontSize = 11.sp))
                }
            }
        }

        // ------------------------------------------------ controls
        AnimatedVisibility(
            visible = (controls || !session.playing) && error == null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(300)),
        ) {
            ControlsOverlay(
                session = session,
                onPanel = { panel = it; poke() },
                onInteraction = ::poke,
                onBack = { nav.back() },
                accentMain = accent.main,
            )
        }

        AnimatedVisibility(
            visible = panel != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
        ) {
            val p = panel
            if (p != null) OptionsPanel(p, session) { panel = null; poke() }
        }
    }
}

/** Invisible mouse pointer while watching. */
private val HiddenPointer: PointerIcon by lazy {
    val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(image, java.awt.Point(0, 0), "hidden"))
}

/** Draws VLC's pictures, letterboxed to fit. */
@Composable
private fun VideoView(session: PlaybackSession, modifier: Modifier) {
    val frames = session.frames
    Canvas(modifier) {
        frames.frame // redraw on every new picture
        val img = frames.image ?: return@Canvas
        val vw = img.width.toFloat()
        val vh = img.height.toFloat()
        if (vw <= 0f || vh <= 0f) return@Canvas
        val scale = minOf(size.width / vw, size.height / vh)
        val w = vw * scale
        val h = vh * scale
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        )
    }
}

@Composable
private fun ControlsOverlay(
    session: PlaybackSession,
    onPanel: (Panel) -> Unit,
    onInteraction: () -> Unit,
    onBack: () -> Unit,
    accentMain: Color,
) {
    val nav = LocalNav.current
    val window = LocalWindowControls.current
    val v = session.fullVideo
    val choice = session.choice
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(230.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.62f to Color(0xE0000000), 1f to Color(0xF2000000)))
        )
        Row(
            Modifier.padding(start = 20.dp, top = 24.dp, end = 300.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton(onBack)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                NaoText(v.title, NaoType.Title.copy(fontSize = 20.sp, color = Color.White), maxLines = 1)
                val chapter = session.info?.streamSegments?.lastOrNull { it.startTimeSeconds * 1000L <= session.position }?.title
                NaoText(
                    listOfNotNull(v.channelName.takeIf { it.isNotBlank() }, chapter?.let { "Chapitre : $it" }).joinToString(" · "),
                    NaoType.Meta.copy(fontSize = 11.sp, color = NaoColors.Body),
                    maxLines = 1,
                )
            }
        }
        if (choice != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 48.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp)
            ) { NaoText(if (choice.isLive) "EN DIRECT" else choice.label, NaoType.Badge.copy(fontSize = 9.sp)) }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val live = choice?.isLive == true
            if (!live) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    NaoText(Fmt.time(session.position), NaoType.Button.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium))
                    NaoText(Fmt.time(session.duration), NaoType.Button.copy(fontSize = 12.5.sp, color = NaoColors.TextMuted, fontWeight = FontWeight.Medium))
                }
                SeekBar(session, accentMain, onInteraction)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).focusGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!live) RoundIconButton("ic_replay", { session.seekBy(-10_000); onInteraction() }, size = 38.dp, iconSize = 16.dp)
                    RoundIconButton(
                        if (session.playing) "ic_pause" else "ic_play",
                        { session.togglePlay(); onInteraction() },
                        size = 48.dp,
                        iconSize = 18.dp,
                        filled = true,
                    )
                    if (!live) RoundIconButton("ic_forward", { session.seekBy(10_000); onInteraction() }, size = 38.dp, iconSize = 16.dp)
                    val next = remember(session.info) { session.nextVideo() }
                    if (next != null) {
                        RoundIconButton("ic_skip_next", { nav.push(Screen.Player(next)) }, size = 38.dp, iconSize = 15.dp)
                    }
                    VolumeControl(session, onInteraction)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (choice != null && choice.subtitles.isNotEmpty()) {
                        PillButton(
                            if (choice.subtitle != null) "Sous-titres · ${choice.subtitle.languageTag ?: ""}" else "Sous-titres",
                            { onPanel(Panel.Subtitles) },
                            icon = "ic_cc",
                            height = 32.dp,
                        )
                    }
                    if (choice != null && choice.qualities.size > 1) {
                        PillButton("Qualité · ${choice.label}", { onPanel(Panel.Quality) }, icon = "ic_settings", height = 32.dp)
                    }
                    if (choice?.isLive != true) {
                        PillButton("Vitesse · ${speedLabel(session.speed)}", { onPanel(Panel.Speed) }, icon = "ic_speed", height = 32.dp)
                    }
                    RoundIconButton(
                        "ic_fullscreen",
                        { window.toggle(); onInteraction() },
                        size = 32.dp,
                        iconSize = 14.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeControl(session: PlaybackSession, onInteraction: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.hoverable(hover).padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusSurface(
            onClick = { session.toggleMute(); onInteraction() },
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            glow = false,
            ring = false,
            color = Color.Transparent,
            focusedColor = Color(0x33FFFFFF),
        ) {
            NaoIcon(
                if (session.muted || session.volume == 0) "ic_volume_off" else "ic_volume",
                Color.White,
                17.dp,
                Modifier.align(Alignment.Center),
            )
        }
        val accent = LocalAccent.current.main
        val level = if (session.muted) 0f else session.volume / 150f
        BoxWithConstraints(
            Modifier
                .width(if (hovered) 90.dp else 60.dp)
                .height(20.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        session.setVolumeTo(((pos.x / size.width) * 150).roundToInt())
                        onInteraction()
                    }
                }
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x38FFFFFF)))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(level.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        }
    }
}

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s.toString().replace('.', ',')}×"

@Composable
private fun SeekBar(session: PlaybackSession, accent: Color, onInteraction: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val d = session.duration.coerceAtLeast(1)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(session) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: continue
                        val fraction = (pos.x / size.width).coerceIn(0f, 1f)
                        when (event.type) {
                            PointerEventType.Move -> hoverX = fraction
                            PointerEventType.Exit -> hoverX = null
                            PointerEventType.Press -> {
                                session.seekTo(fraction)
                                onInteraction()
                            }
                        }
                    }
                }
            }
            .focusable(interactionSource = interaction)
    ) {
        val w = maxWidth
        val active = focused || hovered
        val trackH = if (active) 6.dp else 4.dp
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(trackH).clip(RoundedCornerShape(3.dp)).background(Color(0x38FFFFFF)))
        session.segments.forEach { seg ->
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset(x = w * (seg.startMs.toFloat() / d).coerceIn(0f, 1f))
                    .width((w * ((seg.endMs - seg.startMs).toFloat() / d)).coerceAtLeast(2.dp))
                    .height(trackH)
                    .background(NaoColors.SponsorGreen)
            )
        }
        val frac = (session.position.toFloat() / d).coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.CenterStart).width(w * frac).height(trackH).clip(RoundedCornerShape(3.dp)).background(accent))
        session.info?.streamSegments?.forEach { ch ->
            val x = ch.startTimeSeconds * 1000f / d
            if (x > 0.005f && x < 0.995f) {
                Box(Modifier.align(Alignment.CenterStart).offset(x = w * x).width(1.5.dp).height(trackH).background(Color.Black))
            }
        }
        val knob = if (active) 16.dp else 12.dp
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = w * frac - knob / 2).size(knob).clip(CircleShape)
                .background(Color.White)
                .then(if (active) Modifier.border(3.dp, accent.copy(alpha = 0.6f), CircleShape) else Modifier)
        )
        val hx = hoverX
        if (hx != null && hovered) {
            Box(
                Modifier.align(Alignment.TopStart).offset(x = (w * hx - 24.dp).coerceIn(0.dp, w - 48.dp), y = (-26).dp)
                    .width(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xE6121216)).padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) { NaoText(Fmt.time((d * hx).toLong()), NaoType.Badge.copy(fontSize = 9.sp)) }
        }
    }
}

@Composable
private fun OptionsPanel(panel: Panel, session: PlaybackSession, onClose: () -> Unit) {
    val choice = session.choice
    class Option(val label: String, val selected: Boolean, val action: () -> Unit)
    val (title, options) = when (panel) {
        Panel.Quality -> "Qualité" to choice?.qualities.orEmpty().map { h ->
            Option(StreamPicker.qualityLabel(h, 0), h == choice?.height) {
                session.load(keepPosition = session.position, height = h)
            }
        }
        Panel.Subtitles -> "Sous-titres" to buildList {
            add(Option("Désactivés", choice?.subtitle == null) {
                session.load(keepPosition = session.position, subtitlesOff = true)
            })
            choice?.subtitles.orEmpty().forEach { s ->
                val name = (s.displayLanguageName ?: s.languageTag ?: "?") + if (s.isAutoGenerated) " (auto)" else ""
                add(Option(name, s.content == choice?.subtitle?.content) {
                    session.load(keepPosition = session.position, subtitle = s)
                })
            }
        }
        Panel.Speed -> "Vitesse" to listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { sp ->
            Option(if (sp == 1f) "Normale" else speedLabel(sp), session.speed == sp) { session.changeSpeed(sp) }
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(panel) { delay(80); runCatching { first.requestFocus() } }
    Column(
        Modifier
            .padding(end = 32.dp)
            .width(250.dp)
            .fillMaxHeight(0.82f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF0121216))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NaoText(title, NaoType.Title.copy(fontSize = 16.sp))
        LazyColumn(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            itemsIndexed(options) { i, o ->
                val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
                FocusSurface(
                    onClick = { o.action(); onClose() },
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                        .then(if (i == selectedIndex) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.03f,
                    glow = false,
                    ring = false,
                    color = Color.Transparent,
                    focusedColor = Color.White,
                ) { focused ->
                    Row(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val c = if (focused) Color(0xFF0B0B0D) else Color.White
                        NaoText(o.label, NaoType.Button.copy(fontSize = 12.5.sp, color = c), Modifier.weight(1f), maxLines = 1)
                        if (o.selected) NaoIcon("ic_check", if (focused) c else LocalAccent.current.main, 14.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(modifier.size(46.dp)) {
        drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(4.dp.toPx()))
        drawArc(
            accent, angle, 90f, false,
            topLeft = Offset.Zero, size = Size(size.width, size.height),
            style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
