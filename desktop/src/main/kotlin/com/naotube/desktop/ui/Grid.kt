/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naotube.tv.data.VideoItem

/**
 * Four-column video grid. [header] items span the full width (title, search box…).
 * [onEndReached] is called when the user gets close to the last row (infinite scrolling).
 */
@Composable
fun VideoGrid(
    gridKey: String,
    videos: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 86.dp,
    topPadding: Dp = 0.dp,
    showChannel: Boolean = true,
    onEndReached: (() -> Unit)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    val state = rememberLazyGridState()
    if (onEndReached != null) {
        LaunchedEffect(state, videos.size) {
            snapshotFlow {
                val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= state.layoutInfo.totalItemsCount - 6
            }.collect { if (it && videos.isNotEmpty()) onEndReached() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = state,
        modifier = modifier.focusGroup(),
        contentPadding = PaddingValues(start = startPadding, end = 48.dp, top = topPadding, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        header?.invoke(this)
        itemsIndexed(videos, key = { i, v -> "$gridKey/$i/${v.url}" }) { i, v ->
            VideoCard(
                video = v,
                focusKey = "$gridKey/$i/${v.url}",
                onClick = { onOpen(v) },
                width = 190.dp,
                showChannel = showChannel,
            )
        }
        footer?.invoke(this)
    }
}

fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
