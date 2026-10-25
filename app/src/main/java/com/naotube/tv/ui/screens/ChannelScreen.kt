/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.tv.NaoTubeApp
import com.naotube.tv.R
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.ChannelPage
import com.naotube.tv.data.VideoItem
import com.naotube.tv.ui.InitialFocus
import com.naotube.tv.ui.Load
import com.naotube.tv.ui.Loader
import com.naotube.tv.ui.LocalMemory
import com.naotube.tv.ui.LocalNav
import com.naotube.tv.ui.components.Avatar
import com.naotube.tv.ui.components.MessagePanel
import com.naotube.tv.ui.components.NaoText
import com.naotube.tv.ui.components.NetImage
import com.naotube.tv.ui.components.PillButton
import com.naotube.tv.ui.components.ShimmerRow
import com.naotube.tv.ui.components.VideoGrid
import com.naotube.tv.ui.components.fullWidth
import com.naotube.tv.ui.rememberLoader
import com.naotube.tv.ui.remembered
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoType
import kotlinx.coroutines.launch

private class ChannelVideos {
    var extra by mutableStateOf<List<VideoItem>>(emptyList())
    var next: org.schabi.newpipe.extractor.Page? = null
    var started = false
    var busy = false
}

@Composable
fun ChannelScreen(channel: ChannelItem) {
    val app = NaoTubeApp.instance
    val nav = LocalNav.current
    val memory = LocalMemory.current
    val loader = rememberLoader("channel:${channel.url}") { app.repository.channel(channel.url) }
    val more = remember { memory.get("more") { ChannelVideos() } }
    val subs by app.library.subscriptions.collectAsState()
    val subFocus = remember { FocusRequester() }
    InitialFocus(subFocus)

    val page = (loader.state as? Load.Ok)?.value
    val info = page?.channel ?: channel
    val subscribed = subs.any { it.url == info.url || it.url == channel.url }
    if (page != null && !more.started) {
        more.started = true
        more.next = page.nextPage
    }
    val videos = page?.videos.orEmpty() + more.extra

    fun loadMore(p: ChannelPage) {
        val next = more.next ?: return
        if (more.busy) return
        more.busy = true
        Loader.appScope.launch {
            runCatching { app.repository.channelMore(p, next) }.onSuccess {
                more.next = it.nextPage
                more.extra = (more.extra + it.videos).distinctBy { v -> v.url }
            }
            more.busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (page?.banner != null) {
            NetImage(page.banner, Modifier.fillMaxWidth().height(150.dp))
            Box(
                Modifier.fillMaxWidth().height(150.dp).background(
                    Brush.verticalGradient(listOf(NaoColors.Background.copy(alpha = 0.35f), NaoColors.Background))
                )
            )
        }
        VideoGrid(
            gridKey = "ch",
            videos = videos,
            onOpen = { openVideo(nav, it) },
            modifier = Modifier.fillMaxSize(),
            startPadding = 60.dp,
            topPadding = 70.dp,
            showChannel = false,
            onEndReached = { page?.let { loadMore(it) } },
            header = {
                fullWidth("head") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Avatar(info.avatar ?: channel.avatar, info.name.ifEmpty { channel.name }, 76.dp)
                        Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            NaoText(info.name.ifEmpty { channel.name }, NaoType.Hero.copy(fontSize = 28.sp), maxLines = 1)
                            val n = info.subscriberCount.takeIf { it > 0 } ?: channel.subscriberCount
                            if (n > 0) NaoText(Fmt.subscribers(n), NaoType.Meta)
                            val d = page?.description?.replace(Regex("\\s+"), " ")?.trim()
                            if (!d.isNullOrEmpty()) NaoText(d, NaoType.Body.copy(fontSize = 11.sp), maxLines = 2)
                        }
                        PillButton(
                            if (subscribed) "Abonné" else "S'abonner",
                            { app.library.toggleSubscription(if (page != null) info else channel) },
                            icon = if (subscribed) R.drawable.ic_check else R.drawable.ic_plus,
                            primary = !subscribed,
                            focusRequester = subFocus,
                            modifier = Modifier.remembered("sub"),
                        )
                    }
                }
                fullWidth("title") { NaoText("Vidéos", NaoType.Row) }
                when (val s = loader.state) {
                    Load.Loading -> fullWidth("loading") { ShimmerRow(startPadding = 0.dp) }
                    is Load.Failed -> fullWidth("error") {
                        MessagePanel("Chaîne indisponible", Fmt.error(s.error), actionLabel = "Réessayer", onAction = { loader.reload() })
                    }
                    is Load.Ok -> if (videos.isEmpty()) fullWidth("empty") { NaoText("Aucune vidéo.", NaoType.Body) }
                }
            },
        )
    }
}
