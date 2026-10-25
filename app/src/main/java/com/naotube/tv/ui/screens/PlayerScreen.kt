/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.naotube.tv.NaoTubeApp
import com.naotube.tv.R
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.SkipSegment
import com.naotube.tv.data.SponsorBlock
import com.naotube.tv.data.VideoItem
import com.naotube.tv.data.toVideoItem
import com.naotube.tv.player.ActivePlayer
import com.naotube.tv.player.PlayerEngine
import com.naotube.tv.player.Resolved
import com.naotube.tv.ui.LocalNav
import com.naotube.tv.ui.Screen
import com.naotube.tv.ui.components.MessagePanel
import com.naotube.tv.ui.components.NaoIcon
import com.naotube.tv.ui.components.NaoText
import com.naotube.tv.ui.components.PillButton
import com.naotube.tv.ui.components.RoundIconButton
import com.naotube.tv.ui.theme.LocalAccent
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream

private enum class Panel { Quality, Subtitles, Speed }

/** Everything the player screen needs, outliving recompositions. */
private class PlaybackSession(
    private val context: Context,
    private val scope: CoroutineScope,
    val video: VideoItem,
    private val startMs: Long?,
    private val onEnded: (next: VideoItem?) -> Unit,
) {
    private val app = NaoTubeApp.instance
    val player: ExoPlayer = PlayerEngine.newPlayer(context)

    var info by mutableStateOf<StreamInfo?>(null)
    var resolved by mutableStateOf<Resolved?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var buffering by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var speed by mutableStateOf(1f)
    var segments by mutableStateOf<List<SkipSegment>>(emptyList())
    var skipToast by mutableStateOf<String?>(null)
    var skipToastAt by mutableLongStateOf(0L)

    private var refreshedOnce = false
    private var lastSave = 0L
    private val skipped = HashSet<String>()
    private var loadJob: Job? = null
    private var endedHandled = false

    val fullVideo: VideoItem
        get() = info?.toVideoItem()?.copy(thumbnail = info?.toVideoItem()?.thumbnail ?: video.thumbnail) ?: video

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) loading = false
            if (state == Player.STATE_ENDED && !endedHandled) {
                endedHandled = true
                saveProgress(force = true, finished = true)
                onEnded(nextVideo())
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val http = e.cause as? HttpDataSource.InvalidResponseCodeException
            if (!refreshedOnce && (http?.responseCode == 403 || e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)) {
                // Stream URLs expire: fetch fresh ones once and continue where we were.
                refreshedOnce = true
                load(force = true, keepPosition = player.currentPosition)
                return
            }
            loading = false
            error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Connexion perdue. Vérifie le réseau de ta TV."
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "Ta TV ne sait pas décoder ce format. Essaie le codec H.264 dans les réglages."
                else -> "La lecture a échoué (${e.errorCodeName})."
            }
        }
    }

    init {
        player.addListener(listener)
        ActivePlayer.current = player
    }

    fun load(force: Boolean = false, keepPosition: Long? = null, height: Int? = null,
             subtitle: SubtitlesStream? = null, subtitlesOff: Boolean = false) {
        loadJob?.cancel()
        error = null
        loading = true
        loadJob = scope.launch {
            try {
                val s = app.settings
                val (streamInfo, res) = withContext(Dispatchers.IO) {
                    val i = app.repository.stream(video.url, forceRefresh = force)
                    i to PlayerEngine.resolve(
                        context, app.httpClient, i,
                        maxQuality = s.maxQuality.value,
                        codecPreference = s.codec.value,
                        subtitleMode = s.subtitles.value,
                        overrideHeight = height ?: resolved?.selectedHeight?.takeIf { keepPosition != null && it > 0 },
                        overrideSubtitle = subtitle ?: resolved?.selectedSubtitle?.takeIf { keepPosition != null && !subtitlesOff },
                        subtitlesOff = subtitlesOff,
                    )
                }
                info = streamInfo
                resolved = res
                val start = keepPosition ?: initialPosition(streamInfo)
                player.setMediaSource(res.source, start)
                player.prepare()
                player.playWhenReady = true
                if (segments.isEmpty() && s.sponsorBlock.value && !res.isLive) {
                    segments = app.sponsorBlock.segments(streamInfo.id ?: video.id, s.sponsorCategories.value)
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                loading = false
                error = (t as? IllegalStateException)?.message?.takeIf { it.isNotBlank() && t.cause == null }
                    ?: (t as? IllegalArgumentException)?.message
                    ?: Fmt.error(t)
            }
        }
    }

    private fun initialPosition(i: StreamInfo): Long {
        if (startMs != null) return startMs
        val entry = app.library.progressOf(video.url)
        if (entry != null && entry.inProgress) return (entry.positionMs - 3000).coerceAtLeast(0)
        if (i.startPosition > 0) return i.startPosition * 1000
        return 0L
    }

    /** Called every 250 ms. */
    fun tick() {
        position = player.currentPosition
        duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: duration
        buffered = player.bufferedPosition
        speed = player.playbackParameters.speed
        if (player.isPlaying) {
            val seg = segments.firstOrNull { position >= it.startMs && position < it.endMs - 500 }
            if (seg != null && seg.uuid !in skipped) {
                skipped += seg.uuid
                player.seekTo(seg.endMs)
                skipToast = "${SponsorBlock.label(seg.category)} passé · ${Fmt.duration((seg.endMs - seg.startMs) / 1000)}"
                skipToastAt = System.currentTimeMillis()
            }
            saveProgress()
        }
        if (skipToast != null && System.currentTimeMillis() - skipToastAt > 3500) skipToast = null
    }

    fun saveProgress(force: Boolean = false, finished: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 10_000) return
        if (resolved?.isLive == true || info == null) return
        lastSave = now
        val d = duration.takeIf { it > 0 } ?: return
        val p = if (finished) d else player.currentPosition
        if (p < 5_000 && !finished) return
        app.library.saveProgress(fullVideo, p, d)
    }

    fun nextVideo(): VideoItem? {
        if (!app.settings.autoplay.value) return null
        val history = app.library.history.value
        return info?.relatedItems?.filterIsInstance<StreamInfoItem>()
            ?.map { it.toVideoItem() }
            ?.filter { !it.isShort && !it.isLive && it.url.isNotEmpty() }
            ?.firstOrNull { v -> history.none { it.video.url == v.url && !it.inProgress } }
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(ms: Long) {
        val d = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, d))
        position = player.currentPosition
    }

    fun release() {
        saveProgress(force = true)
        loadJob?.cancel()
        player.removeListener(listener)
        if (ActivePlayer.current === player) ActivePlayer.current = null
        player.release()
    }
}

@Composable
fun PlayerScreen(video: VideoItem, fromStart: Boolean, startMs: Long?) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val session = remember(video.url) {
        PlaybackSession(context, scope, video, if (fromStart) 0L else startMs) { next ->
            if (next != null) nav.push(Screen.Player(next)) else nav.back()
        }
    }
    DisposableEffect(session) {
        session.load()
        onDispose { session.release() }
    }
    LaunchedEffect(session) {
        while (true) {
            session.tick()
            delay(250)
        }
    }

    var controls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var seekFeedback by remember { mutableIntStateOf(0) }
    var seekFeedbackAt by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun poke() {
        lastInteraction = System.currentTimeMillis()
    }
    fun showControls() {
        poke()
        controls = true
    }

    // Auto-hide the controls after 5 s while playing.
    LaunchedEffect(controls, session.playing, panel, lastInteraction) {
        if (controls && session.playing && panel == null) {
            delay(5000)
            if (System.currentTimeMillis() - lastInteraction >= 4900) controls = false
        }
    }
    LaunchedEffect(controls, panel) {
        delay(50)
        if (panel == null) {
            runCatching { if (controls) playFocus.requestFocus() else rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(seekFeedbackAt) {
        if (seekFeedbackAt > 0) {
            delay(900)
            seekFeedback = 0
        }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            controls && session.error == null && session.playing -> controls = false
            else -> nav.back()
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        val native = e.nativeKeyEvent
        if (native.action != AndroidKeyEvent.ACTION_DOWN) return false
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_HEADSETHOOK -> {
                session.togglePlay(); showControls(); return true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { session.player.play(); showControls(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { session.player.pause(); showControls(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { session.seekBy(10_000); showControls(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { session.seekBy(-10_000); showControls(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> {
                session.nextVideo()?.let { nav.push(Screen.Player(it)) }; return true
            }
        }
        if (panel != null) {
            poke()
            return false
        }
        if (!controls) {
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                    session.seekBy(-10_000)
                    seekFeedback = if (seekFeedback < 0) seekFeedback - 10 else -10
                    seekFeedbackAt = System.currentTimeMillis()
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    session.seekBy(10_000)
                    seekFeedback = if (seekFeedback > 0) seekFeedback + 10 else 10
                    seekFeedbackAt = System.currentTimeMillis()
                    return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    session.togglePlay(); showControls(); return true
                }
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> {
                    showControls(); return true
                }
            }
            return false
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
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    keepScreenOn = true
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            AndroidColor.WHITE, 0x99000000.toInt(), AndroidColor.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_NONE, AndroidColor.BLACK, null
                        )
                    )
                    subtitleView?.setFractionalTextSize(0.045f)
                    player = session.player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if ((session.loading || session.buffering) && session.error == null) {
            Spinner(Modifier.align(Alignment.Center))
        }

        // Seek feedback when controls are hidden.
        if (seekFeedback != 0 && !controls) {
            Box(
                Modifier
                    .align(if (seekFeedback > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 80.dp)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NaoIcon(if (seekFeedback > 0) R.drawable.ic_forward else R.drawable.ic_replay, Color.White, 22.dp)
                    NaoText("${if (seekFeedback > 0) "+" else "−"}${kotlin.math.abs(seekFeedback)} s", NaoType.Button.copy(fontSize = 11.sp))
                }
            }
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

        // SponsorBlock toast.
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
                NaoIcon(R.drawable.ic_shield, NaoColors.SponsorGreen, 15.dp)
                Column {
                    NaoText("SPONSORBLOCK", NaoType.Overline.copy(fontSize = 8.sp, color = NaoColors.SponsorGreen))
                    NaoText(session.skipToast ?: "", NaoType.BodyStrong.copy(fontSize = 11.sp))
                }
            }
        }

        AnimatedVisibility(
            visible = controls && error == null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
        ) {
            ControlsOverlay(
                session = session,
                playFocus = playFocus,
                onPanel = { panel = it },
                onInteraction = ::poke,
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
            if (p != null) {
                OptionsPanel(p, session) { panel = null; showControls() }
            }
        }
    }
}

@Composable
private fun ControlsOverlay(
    session: PlaybackSession,
    playFocus: FocusRequester,
    onPanel: (Panel) -> Unit,
    onInteraction: () -> Unit,
    accentMain: Color,
) {
    val v = session.fullVideo
    val res = session.resolved
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(130.dp).background(
                Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent))
            )
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(230.dp).background(
                Brush.verticalGradient(0f to Color.Transparent, 0.62f to Color(0xE0000000), 1f to Color(0xF2000000))
            )
        )
        Column(Modifier.padding(start = 48.dp, top = 32.dp, end = 300.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            NaoText(v.title, NaoType.Title.copy(fontSize = 20.sp, color = Color.White), maxLines = 1)
            val chapter = session.info?.streamSegments
                ?.lastOrNull { it.startTimeSeconds * 1000L <= session.position }?.title
            NaoText(
                listOfNotNull(v.channelName.takeIf { it.isNotBlank() }, chapter?.let { "Chapitre : $it" }).joinToString(" · "),
                NaoType.Meta.copy(fontSize = 11.sp, color = NaoColors.Body),
                maxLines = 1,
            )
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 35.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (res != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp)
                ) { NaoText(if (res.isLive) "EN DIRECT" else res.label, NaoType.Badge.copy(fontSize = 9.sp)) }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val live = res?.isLive == true
            if (!live) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    NaoText(Fmt.time(session.position), NaoType.Button.copy(fontSize = 12.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                    NaoText(Fmt.time(session.duration), NaoType.Button.copy(fontSize = 12.5.sp, color = NaoColors.TextMuted, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                }
                SeekBar(session, accentMain, onInteraction)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).focusGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!live) RoundIconButton(R.drawable.ic_replay, { session.seekBy(-10_000); onInteraction() }, size = 38.dp, iconSize = 16.dp)
                    RoundIconButton(
                        if (session.playing) R.drawable.ic_pause else R.drawable.ic_play,
                        { session.togglePlay(); onInteraction() },
                        size = 48.dp,
                        iconSize = 18.dp,
                        filled = true,
                        focusRequester = playFocus,
                    )
                    if (!live) RoundIconButton(R.drawable.ic_forward, { session.seekBy(10_000); onInteraction() }, size = 38.dp, iconSize = 16.dp)
                    val next = remember(session.info) { session.nextVideo() }
                    if (next != null) {
                        val nav = LocalNav.current
                        RoundIconButton(R.drawable.ic_skip_next, { nav.push(Screen.Player(next)) }, size = 38.dp, iconSize = 15.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (res != null && res.subtitles.isNotEmpty()) {
                        PillButton(
                            if (res.selectedSubtitle != null) "Sous-titres · ${res.selectedSubtitle.languageTag ?: ""}" else "Sous-titres",
                            { onPanel(Panel.Subtitles) },
                            icon = R.drawable.ic_cc,
                            height = 32.dp,
                        )
                    }
                    if (res != null && res.qualities.size > 1) {
                        PillButton("Qualité · ${res.label}", { onPanel(Panel.Quality) }, icon = R.drawable.ic_settings, height = 32.dp)
                    }
                    if (res?.isLive != true) {
                        PillButton("Vitesse · ${speedLabel(session.speed)}", { onPanel(Panel.Speed) }, icon = R.drawable.ic_speed, height = 32.dp)
                    }
                }
            }
        }
    }
}

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s.toString().replace('.', ',')}×"

@Composable
private fun SeekBar(session: PlaybackSession, accent: Color, onInteraction: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val d = session.duration.coerceAtLeast(1)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .onKeyEvent {
                val n = it.nativeKeyEvent
                if (n.action != AndroidKeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (n.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { session.seekBy(-10_000); onInteraction(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { session.seekBy(10_000); onInteraction(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { session.togglePlay(); onInteraction(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
    ) {
        val w = maxWidth
        val trackH = if (focused) 6.dp else 4.dp
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(trackH).clip(RoundedCornerShape(3.dp)).background(Color(0x38FFFFFF)))
        Box(
            Modifier.align(Alignment.CenterStart).width(w * (session.buffered.toFloat() / d).coerceIn(0f, 1f)).height(trackH)
                .clip(RoundedCornerShape(3.dp)).background(Color(0x52FFFFFF))
        )
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
        val knob = if (focused) 16.dp else 12.dp
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = w * frac - knob / 2).size(knob).clip(CircleShape)
                .background(Color.White)
                .then(if (focused) Modifier.border(3.dp, accent.copy(alpha = 0.6f), CircleShape) else Modifier)
        )
    }
}

@Composable
private fun OptionsPanel(panel: Panel, session: PlaybackSession, onClose: () -> Unit) {
    val res = session.resolved
    data class Option(val label: String, val selected: Boolean, val action: () -> Unit)
    val (title, options) = when (panel) {
        Panel.Quality -> "Qualité" to (res?.qualities.orEmpty()).map { h ->
            Option(PlayerEngine.qualityLabel(h, 0), h == res?.selectedHeight) {
                session.load(keepPosition = session.player.currentPosition, height = h)
            }
        }
        Panel.Subtitles -> "Sous-titres" to buildList {
            add(Option("Désactivés", res?.selectedSubtitle == null) {
                session.load(keepPosition = session.player.currentPosition, subtitlesOff = true)
            })
            res?.subtitles.orEmpty().forEach { s ->
                val name = (s.displayLanguageName ?: s.languageTag ?: "?") + if (s.isAutoGenerated) " (auto)" else ""
                add(Option(name, s.content == res?.selectedSubtitle?.content) {
                    session.load(keepPosition = session.player.currentPosition, subtitle = s)
                })
            }
        }
        Panel.Speed -> "Vitesse" to listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { sp ->
            Option(if (sp == 1f) "Normale" else speedLabel(sp), session.speed == sp) {
                session.player.setPlaybackSpeed(sp)
            }
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
                com.naotube.tv.ui.components.FocusSurface(
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
                        if (o.selected) NaoIcon(R.drawable.ic_check, if (focused) c else LocalAccent.current.main, 14.dp)
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
        drawArc(accent, angle, 90f, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            topLeft = Offset.Zero, size = Size(size.width, size.height))
    }
}
