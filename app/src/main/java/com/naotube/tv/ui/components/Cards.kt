/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naotube.tv.NaoTubeApp
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.VideoItem
import com.naotube.tv.ui.remembered
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoType

val CardWidth: Dp = 184.dp
val CardThumbHeight: Dp = 103.5.dp

fun VideoItem.metaLine(): String {
    val parts = mutableListOf<String>()
    if (isLive) parts += "En direct" else Fmt.views(viewCount).takeIf { it.isNotEmpty() }?.let { parts += it }
    val date = uploadedAtMs?.let { Fmt.relative(it) } ?: uploadedText
    if (!date.isNullOrBlank() && !isLive) parts += date
    return parts.joinToString(" · ")
}

@Composable
fun VideoCard(
    video: VideoItem,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardWidth,
    showChannel: Boolean = true,
    onFocused: ((VideoItem) -> Unit)? = null,
) {
    val history by NaoTubeApp.instance.library.history.collectAsState()
    val progress = remember(history, video.url) {
        history.firstOrNull { it.video.url == video.url }?.progress ?: 0f
    }
    var focused by remember { mutableStateOf(false) }
    val thumbHeight = width * (9f / 16f)
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .width(width)
                .height(thumbHeight)
                .remembered(focusKey),
            shape = RoundedCornerShape(9.dp),
            focusedScale = 1.07f,
            onFocusChange = {
                focused = it
                if (it) onFocused?.invoke(video)
            },
        ) {
            NetImage(video.thumbnail, Modifier.matchParentSize())
            val badge = when {
                video.isLive -> "EN DIRECT"
                video.durationSec > 0 -> Fmt.duration(video.durationSec)
                else -> null
            }
            if (badge != null) {
                DurationBadge(
                    badge,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = if (progress > 0f) 9.dp else 6.dp),
                    live = video.isLive,
                )
            }
            if (progress > 0.01f) {
                ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NaoText(
                video.title,
                NaoType.CardTitle,
                color = if (focused) Color.White else NaoColors.CardTitle,
                maxLines = 2,
            )
            val sub = if (showChannel) video.channelName else video.metaLine()
            if (sub.isNotBlank()) NaoText(sub, NaoType.CardMeta, maxLines = 1)
        }
    }
}

/** Horizontal row with a title; focus comes back to the last focused card. */
@Composable
fun VideoRow(
    rowKey: String,
    title: String,
    videos: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    startPadding: Dp = 86.dp,
    onFocused: ((VideoItem) -> Unit)? = null,
    onEndReached: (() -> Unit)? = null,
) {
    val state = rememberLazyListState()
    if (onEndReached != null) {
        LaunchedEffect(state, videos.size) {
            snapshotFlow {
                val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= videos.size - 4
            }.collect { if (it) onEndReached() }
        }
    }
    Column(modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(
            Modifier.padding(start = startPadding),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            NaoText(title, NaoType.Row)
            if (subtitle != null) NaoText(subtitle, NaoType.CardMeta.copy(color = NaoColors.TextDim))
        }
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(start = startPadding, end = 48.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            itemsIndexed(videos, key = { i, v -> "$i-${v.url}" }) { i, v ->
                VideoCard(
                    video = v,
                    focusKey = "$rowKey/$i/${v.url}",
                    onClick = { onOpen(v) },
                    onFocused = onFocused,
                )
            }
        }
    }
}

@Composable
fun ChannelBubble(channel: ChannelItem, focusKey: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .size(72.dp)
                .remembered(focusKey),
            shape = CircleShape,
            focusedScale = 1.08f,
            onFocusChange = { focused = it },
        ) {
            Avatar(channel.avatar, channel.name, 72.dp)
        }
        NaoText(
            channel.name,
            NaoType.CardTitle,
            color = if (focused) Color.White else NaoColors.CardTitle,
            maxLines = 2,
            align = TextAlign.Center,
        )
    }
}

@Composable
fun ChannelRow(
    rowKey: String,
    title: String,
    channels: List<ChannelItem>,
    onOpen: (ChannelItem) -> Unit,
    startPadding: Dp = 86.dp,
) {
    Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        NaoText(title, NaoType.Row, Modifier.padding(start = startPadding))
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(start = startPadding, end = 48.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(channels, key = { i, c -> "$i-${c.url}" }) { i, c ->
                ChannelBubble(c, "$rowKey/$i/${c.url}", onClick = { onOpen(c) })
            }
        }
    }
}

/** Placeholder row shown while a section loads. */
@Composable
fun ShimmerRow(startPadding: Dp = 86.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Shimmer(Modifier.padding(start = startPadding).width(140.dp).height(14.dp), RoundedCornerShape(4.dp))
        Row(Modifier.padding(start = startPadding, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            repeat(5) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Shimmer(Modifier.width(CardWidth).height(CardThumbHeight))
                    Shimmer(Modifier.width(150.dp).height(9.dp), RoundedCornerShape(3.dp))
                }
            }
        }
    }
}

@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(NaoColors.Live))
}
