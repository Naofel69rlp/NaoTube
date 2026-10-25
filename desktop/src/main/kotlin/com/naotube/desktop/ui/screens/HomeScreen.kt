/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.desktop.Nao
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.Section
import com.naotube.tv.data.VideoItem
import com.naotube.desktop.ui.InitialFocus
import com.naotube.desktop.ui.Load
import com.naotube.desktop.ui.LocalMemory
import com.naotube.desktop.ui.LocalNav
import com.naotube.desktop.ui.Screen
import com.naotube.desktop.ui.MessagePanel
import com.naotube.desktop.ui.NaoText
import com.naotube.desktop.ui.NetImage
import com.naotube.desktop.ui.PillButton
import com.naotube.desktop.ui.ShimmerRow
import com.naotube.desktop.ui.StatusCorner
import com.naotube.desktop.ui.VideoRow
import com.naotube.desktop.ui.metaLine
import com.naotube.desktop.ui.rememberLoader
import com.naotube.desktop.ui.remembered
import com.naotube.desktop.ui.LocalAccent
import com.naotube.desktop.ui.NaoColors
import com.naotube.desktop.ui.NaoType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private val KIOSKS = listOf(
    "trending_music" to "Tendances musique",
    "trending_gaming" to "Jeux vidéo",
    "live" to "En direct",
    "trending_movies_and_shows" to "Films et bandes-annonces",
    "trending_podcasts_episodes" to "Podcasts",
)

/** Opens a video: the details page, or straight into the player for lives. */
fun openVideo(nav: com.naotube.desktop.ui.Navigator, video: VideoItem) {
    if (video.isLive) nav.push(Screen.Player(video)) else nav.push(Screen.Detail(video))
}

@Composable
fun HomeScreen() {
    val app = Nao
    val nav = LocalNav.current
    val memory = LocalMemory.current
    val subs by app.library.subscriptions.collectAsState()
    val history by app.library.history.collectAsState()

    val loader = rememberLoader("home") {
        coroutineScope {
            val feed = async {
                runCatching { app.repository.subscriptionFeed(app.library.subscriptions.value, 40) }
                    .getOrDefault(emptyList())
            }
            val kiosks = KIOSKS.map { (id, title) ->
                async { runCatching { Section(id, title, null, app.repository.kiosk(id)) }.getOrNull() }
            }
            val out = mutableListOf<Section>()
            feed.await().takeIf { it.isNotEmpty() }?.let {
                out += Section("subs", "Nouveautés de tes abonnements", null, it)
            }
            kiosks.forEach { k -> k.await()?.takeIf { it.videos.isNotEmpty() }?.let { out += it } }
            if (out.isEmpty()) throw java.io.IOException("YouTube n'a renvoyé aucune vidéo.")
            out
        }
    }

    val resume = remember(history) { history.filter { it.inProgress }.map { it.video }.take(20) }
    var hero by remember { mutableStateOf(memory.get<VideoItem?>("hero") { null }) }
    fun setHero(v: VideoItem) {
        hero = v
        memory.data["hero"] = v
    }

    val sections = (loader.state as? Load.Ok)?.value.orEmpty()
    val shownHero = hero ?: resume.firstOrNull() ?: sections.firstOrNull()?.videos?.firstOrNull()

    val firstFocus = remember { FocusRequester() }
    InitialFocus(firstFocus, ready = shownHero != null || loader.state is Load.Failed)

    Box(Modifier.fillMaxSize()) {
        // ------------------------------------------------ backdrop
        Crossfade(targetState = shownHero, animationSpec = tween(450), label = "hero") { v ->
            Box(Modifier.fillMaxSize()) {
                if (v != null) {
                    NetImage(
                        url = v.hiResThumbnail,
                        fallbackUrl = v.thumbnail,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxWidth(0.74f)
                            .fillMaxHeight(0.74f),
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.74f)
                .background(
                    Brush.horizontalGradient(
                        0f to NaoColors.Background,
                        0.34f to NaoColors.Background.copy(alpha = 0.82f),
                        1f to Color.Transparent,
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.4f to Color.Transparent,
                        0.62f to NaoColors.Background.copy(alpha = 0.85f),
                        0.75f to NaoColors.Background,
                    )
                )
        )

        StatusCorner(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 32.dp))

        // ------------------------------------------------ hero text
        if (shownHero != null) {
            HeroBlock(
                video = shownHero,
                isResume = resume.any { it.url == shownHero.url },
                modifier = Modifier.padding(start = 86.dp, top = 70.dp).width(430.dp),
                onPlay = { nav.push(Screen.Player(shownHero)) },
                onDetails = { openVideo(nav, shownHero) },
                onChannel = {
                    if (shownHero.channelUrl.isNotEmpty()) {
                        nav.push(
                            Screen.Channel(
                                ChannelItem(shownHero.channelUrl, shownHero.channelName, shownHero.channelAvatar)
                            )
                        )
                    }
                },
            )
        }

        // ------------------------------------------------ rows
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 330.dp)
                .focusGroup(),
            contentPadding = PaddingValues(bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            var first = true
            if (resume.isNotEmpty()) {
                val isFirst = first
                first = false
                item(key = "resume") {
                    Box(if (isFirst) Modifier.remembered("first-row", firstFocus) else Modifier) {
                        VideoRow(
                            rowKey = "resume",
                            title = "Reprendre",
                            subtitle = "${resume.size} vidéo${if (resume.size > 1) "s" else ""} en cours",
                            videos = resume,
                            onOpen = { nav.push(Screen.Player(it)) },
                            onFocused = ::setHero,
                        )
                    }
                }
            }
            when (val s = loader.state) {
                Load.Loading -> items(3) { ShimmerRow() }
                is Load.Failed -> item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MessagePanel(
                            "YouTube ne répond pas",
                            Fmt.error(s.error),
                            actionLabel = "Réessayer",
                            onAction = { loader.reload() },
                            focusRequester = if (resume.isEmpty()) firstFocus else null,
                        )
                    }
                }
                is Load.Ok -> items(s.value, key = { it.id }) { section ->
                    val isFirst = first && section === s.value.first()
                    Box(if (isFirst) Modifier.remembered("first-row", firstFocus) else Modifier) {
                        VideoRow(
                            rowKey = section.id,
                            title = section.title,
                            videos = section.videos,
                            onOpen = { openVideo(nav, it) },
                            onFocused = ::setHero,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBlock(
    video: VideoItem,
    isResume: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onChannel: () -> Unit,
) {
    val app = Nao
    val accent = LocalAccent.current
    val later by app.library.watchLater.collectAsState()
    val inLater = later.any { it.url == video.url }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            NaoText(
                if (isResume) "REPRENDRE" else if (video.isLive) "EN DIRECT" else "À LA UNE",
                NaoType.Overline,
                color = accent.text,
            )
            Box(Modifier.width(20.dp).height(1.dp).background(accent.text))
            NaoText(video.channelName, NaoType.Meta.copy(fontSize = 10.sp), maxLines = 1)
        }
        NaoText(video.title, NaoType.Hero, maxLines = 2)
        val meta = listOf(video.metaLine(), Fmt.duration(video.durationSec)).filter { it.isNotBlank() }
        NaoText(meta.joinToString(" · "), NaoType.Meta, maxLines = 1)
        val desc = video.description?.replace(Regex("\\s+"), " ")?.trim()
        if (!desc.isNullOrEmpty()) NaoText(desc, NaoType.Body, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PillButton("Lire", onPlay, icon = "ic_play", primary = true, modifier = Modifier.remembered("hero-play"))
            PillButton("Infos", onDetails, icon = "ic_info", modifier = Modifier.remembered("hero-info"))
            PillButton(
                if (inLater) "Dans ma liste" else "Plus tard",
                { app.library.toggleWatchLater(video) },
                icon = if (inLater) "ic_check" else "ic_clock",
            )
            if (video.channelUrl.isNotEmpty()) {
                PillButton("Chaîne", onChannel, icon = "ic_person")
            }
        }
    }
}
