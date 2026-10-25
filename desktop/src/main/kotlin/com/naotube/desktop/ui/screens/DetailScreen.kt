/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui.screens

import org.jsoup.Jsoup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.desktop.Nao
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.VideoItem
import com.naotube.tv.data.bestUrl
import com.naotube.tv.data.toVideoItem
import com.naotube.desktop.ui.InitialFocus
import com.naotube.desktop.ui.Load
import com.naotube.desktop.ui.LocalNav
import com.naotube.desktop.ui.Screen
import com.naotube.desktop.ui.Avatar
import com.naotube.desktop.ui.FocusSurface
import com.naotube.desktop.ui.MessagePanel
import com.naotube.desktop.ui.NaoIcon
import com.naotube.desktop.ui.NaoText
import com.naotube.desktop.ui.NetImage
import com.naotube.desktop.ui.PillButton
import com.naotube.desktop.ui.Shimmer
import com.naotube.desktop.ui.StatusCorner
import com.naotube.desktop.ui.Tag
import com.naotube.desktop.ui.VideoRow
import com.naotube.desktop.ui.rememberLoader
import com.naotube.desktop.ui.remembered
import com.naotube.desktop.ui.LocalAccent
import com.naotube.desktop.ui.NaoColors
import com.naotube.desktop.ui.NaoType
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem

fun descriptionText(d: Description?): String {
    if (d == null) return ""
    val raw = d.content() ?: return ""
    return if (d.type() == Description.Type.HTML) {
        Jsoup.parse(raw.replace("<br>", "\n")).wholeText().trim()
    } else {
        raw.trim()
    }
}

@Composable
fun DetailScreen(video: VideoItem) {
    val app = Nao
    val nav = LocalNav.current
    val accent = LocalAccent.current
    val loader = rememberLoader("detail:${video.url}") { app.repository.stream(video.url) }
    val history by app.library.history.collectAsState()
    val later by app.library.watchLater.collectAsState()
    val subs by app.library.subscriptions.collectAsState()
    val entry = history.firstOrNull { it.video.url == video.url }?.takeIf { it.inProgress }
    val playFocus = remember { FocusRequester() }
    InitialFocus(playFocus)

    val info = (loader.state as? Load.Ok)?.value
    val full = info?.toVideoItem()?.copy(description = null) ?: video
    val channel = ChannelItem(
        url = full.channelUrl,
        name = full.channelName,
        avatar = info?.uploaderAvatars?.bestUrl(96) ?: video.channelAvatar,
        subscriberCount = info?.uploaderSubscriberCount ?: -1,
    )
    val subscribed = subs.any { it.url == channel.url }

    Box(Modifier.fillMaxSize()) {
        NetImage(full.hiResThumbnail, Modifier.fillMaxSize(), fallbackUrl = full.thumbnail ?: video.thumbnail)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to NaoColors.Background.copy(alpha = 0.97f),
                    0.38f to NaoColors.Background.copy(alpha = 0.86f),
                    0.75f to NaoColors.Background.copy(alpha = 0.25f),
                    1f to NaoColors.Background.copy(alpha = 0.1f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    0.8f to NaoColors.Background.copy(alpha = 0.88f),
                    1f to NaoColors.Background,
                )
            )
        )
        StatusCorner(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 32.dp), showBadge = false)

        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(top = 52.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(key = "head") {
                Column(Modifier.padding(start = 60.dp).width(480.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    if (info != null) {
                        val heights = info.videoOnlyStreams.map { it.height }
                        val tags = buildList {
                            val max = heights.maxOrNull() ?: 0
                            if (max >= 2160) add("4K") else if (max >= 1440) add("1440p") else if (max >= 1080) add("HD")
                            if (info.videoOnlyStreams.any { it.fps > 30 }) add("60 i/s")
                            if (info.subtitles.any { it.languageTag?.startsWith("fr") == true }) add("Sous-titres FR")
                            if (info.streamSegments.isNotEmpty()) add("${info.streamSegments.size} chapitres")
                            Fmt.category(info.category)?.let { add(it) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { tags.forEach { Tag(it) } }
                    }
                    NaoText(full.title.ifEmpty { video.title }, NaoType.Hero.copy(fontSize = 33.sp), maxLines = 3)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Avatar(channel.avatar, channel.name, 32.dp)
                        Column {
                            NaoText(channel.name, NaoType.BodyStrong.copy(fontSize = 12.5.sp), maxLines = 1)
                            if (channel.subscriberCount > 0) NaoText(Fmt.subscribers(channel.subscriberCount), NaoType.Meta.copy(fontSize = 9.5.sp))
                        }
                        Spacer(Modifier.width(6.dp))
                        if (channel.url.isNotEmpty()) {
                            PillButton(
                                if (subscribed) "Abonné" else "S'abonner",
                                { app.library.toggleSubscription(channel) },
                                icon = if (subscribed) "ic_check" else "ic_plus",
                                height = 28.dp,
                            )
                            PillButton("Chaîne", { nav.push(Screen.Channel(channel)) }, height = 28.dp)
                        }
                    }
                    val meta = buildList {
                        Fmt.views(full.viewCount).takeIf { it.isNotEmpty() }?.let { add(it) }
                        (full.uploadedAtMs?.let { Fmt.relative(it) } ?: full.uploadedText)?.takeIf { it.isNotBlank() }?.let { add(it) }
                        Fmt.duration(full.durationSec).takeIf { it.isNotEmpty() }?.let { add(it) }
                        info?.likeCount?.takeIf { it > 0 }?.let { add("${Fmt.compact(it)} j'aime") }
                    }
                    NaoText(meta.joinToString("  ·  "), NaoType.Meta.copy(fontSize = 10.5.sp, color = NaoColors.Body))
                    when (val s = loader.state) {
                        Load.Loading -> Shimmer(Modifier.width(400.dp).height(40.dp), RoundedCornerShape(6.dp))
                        is Load.Failed -> NaoText(Fmt.error(s.error), NaoType.Body.copy(color = accent.text))
                        is Load.Ok -> {
                            val d = descriptionText(s.value.description)
                            if (d.isNotEmpty()) NaoText(d, NaoType.Body.copy(fontSize = 11.5.sp), maxLines = 3)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (entry != null) {
                            PillButton(
                                "Reprendre à ${Fmt.time(entry.positionMs)}",
                                { nav.push(Screen.Player(full)) },
                                icon = "ic_play",
                                primary = true,
                                height = 38.dp,
                                focusRequester = playFocus,
                                progress = entry.progress,
                                modifier = Modifier.remembered("play"),
                            )
                            PillButton(
                                "Depuis le début",
                                { nav.push(Screen.Player(full, fromStart = true)) },
                                icon = "ic_replay",
                                height = 38.dp,
                            )
                        } else {
                            PillButton(
                                "Lire",
                                { nav.push(Screen.Player(full)) },
                                icon = "ic_play",
                                primary = true,
                                height = 38.dp,
                                focusRequester = playFocus,
                                modifier = Modifier.remembered("play"),
                            )
                        }
                        val inLater = later.any { it.url == video.url }
                        PillButton(
                            if (inLater) "Dans ma liste" else "Plus tard",
                            { app.library.toggleWatchLater(full) },
                            icon = if (inLater) "ic_check" else "ic_clock",
                            height = 38.dp,
                        )
                        if (loader.state is Load.Failed) {
                            PillButton("Réessayer", { loader.reload() }, icon = "ic_refresh", height = 38.dp)
                        }
                    }
                }
            }
            val segments = info?.streamSegments.orEmpty()
            if (segments.isNotEmpty()) {
                item(key = "chapters") {
                    Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        NaoText("Chapitres", NaoType.Row.copy(fontSize = 14.sp), Modifier.padding(start = 60.dp))
                        LazyRow(
                            Modifier.fillMaxWidth().focusRestorer(),
                            contentPadding = PaddingValues(start = 60.dp, end = 48.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            itemsIndexed(segments) { i, seg ->
                                FocusSurface(
                                    onClick = { nav.push(Screen.Player(full, startMs = seg.startTimeSeconds * 1000L)) },
                                    modifier = Modifier.width(158.dp).height(46.dp).remembered("chap/$i"),
                                    shape = RoundedCornerShape(9.dp),
                                    focusedScale = 1.05f,
                                    color = Color(0x0FFFFFFF),
                                    focusedColor = accent.main.copy(alpha = 0.22f),
                                ) { focused ->
                                    Box(Modifier.matchParentSize().border(1.dp, if (focused) accent.main else Color(0x1AFFFFFF), RoundedCornerShape(9.dp)))
                                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 11.dp)) {
                                        NaoText(Fmt.time(seg.startTimeSeconds * 1000L), NaoType.Button.copy(fontSize = 9.5.sp, color = if (focused) Color.White else Color(0xFFFF6B6B)))
                                        NaoText(seg.title ?: "Chapitre ${i + 1}", NaoType.BodyStrong.copy(fontSize = 10.5.sp), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val related = info?.relatedItems?.filterIsInstance<StreamInfoItem>()?.map { it.toVideoItem() }
                ?.filter { !it.isShort && it.url.isNotEmpty() }.orEmpty()
            if (related.isNotEmpty()) {
                item(key = "related") {
                    VideoRow(
                        rowKey = "related",
                        title = "À suivre",
                        videos = related,
                        onOpen = { nav.push(Screen.Detail(it)) },
                        startPadding = 60.dp,
                    )
                }
            }
        }
    }
}
