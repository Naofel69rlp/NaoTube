/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.desktop.Nao
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.SearchPage
import com.naotube.tv.data.VideoItem
import com.naotube.desktop.ui.InitialFocus
import com.naotube.desktop.ui.LocalMemory
import com.naotube.desktop.ui.LocalNav
import com.naotube.desktop.ui.Screen
import com.naotube.desktop.ui.ChannelBubble
import com.naotube.desktop.ui.Chip
import com.naotube.desktop.ui.MessagePanel
import com.naotube.desktop.ui.NaoIcon
import com.naotube.desktop.ui.NaoText
import com.naotube.desktop.ui.RoundIconButton
import com.naotube.desktop.ui.ShimmerRow
import com.naotube.desktop.ui.StatusCorner
import com.naotube.desktop.ui.VideoGrid
import com.naotube.desktop.ui.fullWidth
import com.naotube.desktop.ui.remembered
import com.naotube.desktop.ui.LocalAccent
import com.naotube.desktop.ui.NaoColors
import com.naotube.desktop.ui.NaoType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search state kept in the screen memory (survives opening a video and coming back). */
private class SearchState {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
    var channels by mutableStateOf<List<ChannelItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var page: SearchPage? = null

    /** Set by a new search: move the focus to the results once they arrive. */
    var focusResults = false
    private var job: Job? = null
    private var moreJob: Job? = null
    private var suggestJob: Job? = null

    fun suggest(scope: CoroutineScope) {
        suggestJob?.cancel()
        val q = query
        suggestJob = scope.launch {
            delay(250)
            suggestions = runCatching { Nao.repository.suggestions(q) }
                .getOrDefault(emptyList()).take(8)
        }
    }

    fun search(scope: CoroutineScope, q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        submitted = text
        focusResults = true
        job?.cancel()
        moreJob?.cancel()
        loading = true
        error = null
        videos = emptyList()
        channels = emptyList()
        job = scope.launch {
            try {
                val p = Nao.repository.search(text)
                page = p
                videos = p.videos
                channels = p.channels
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t
            } finally {
                loading = false
            }
        }
    }

    fun more(scope: CoroutineScope) {
        val p = page ?: return
        if (p.nextPage == null || moreJob?.isActive == true || loading) return
        moreJob = scope.launch {
            runCatching { Nao.repository.searchMore(p) }.onSuccess { next ->
                page = next
                videos = (videos + next.videos).distinctBy { it.url }
                channels = (channels + next.channels).distinctBy { it.url }
            }
        }
    }
}

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val memory = LocalMemory.current
    val accent = LocalAccent.current
    val state = remember { memory.get("search") { SearchState() } }
    val scope = com.naotube.desktop.ui.Loader.appScope
    val fieldFocus = remember { FocusRequester() }
    InitialFocus(fieldFocus)

    val keyboard = LocalSoftwareKeyboardController.current
    // After a search: close the keyboard and put the focus on the first result (channel or
    // video), so the remote moves through the results right away.
    LaunchedEffect(state.loading, state.submitted) {
        if (state.loading || !state.focusResults) return@LaunchedEffect
        state.focusResults = false
        keyboard?.hide()
        val keys = buildList {
            state.channels.firstOrNull()?.let { add("sch/0/${it.url}") }
            state.videos.firstOrNull()?.let { add("search:${state.submitted}/0/${it.url}") }
        }
        if (keys.isEmpty()) return@LaunchedEffect
        repeat(12) {
            delay(80)
            if (memory.focusFirst(keys)) return@LaunchedEffect
        }
    }

    LaunchedEffect(state.query) {
        if (state.query != state.submitted) state.suggest(scope)
    }

    Box(Modifier.fillMaxSize()) {
        StatusCorner(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 32.dp))
        VideoGrid(
            gridKey = "search:${state.submitted}",
            videos = state.videos,
            onOpen = { openVideo(nav, it) },
            modifier = Modifier.fillMaxSize(),
            topPadding = 32.dp,
            onEndReached = { state.more(scope) },
            header = {
                fullWidth("box") {
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        NaoText("RECHERCHE", NaoType.Overline, color = accent.text)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            SearchField(
                                value = state.query,
                                onChange = { state.query = it },
                                onSubmit = { state.search(scope, state.query) },
                                focusRequester = fieldFocus,
                                modifier = Modifier.width(590.dp),
                            )
                            RoundIconButton(
                                "ic_search",
                                { state.search(scope, state.query) },
                                size = 44.dp,
                                iconSize = 17.dp,
                            )
                        }
                        val chips = if (state.query.isNotBlank() && state.query != state.submitted) {
                            state.suggestions
                        } else {
                            emptyList()
                        }
                        if (chips.isNotEmpty()) {
                            LazyRow(
                                Modifier.fillMaxWidth().focusRestorer(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                itemsIndexed(chips) { i, s ->
                                    Chip(s, { state.search(scope, s) }, icon = "ic_search")
                                }
                            }
                        }
                    }
                }
                if (state.channels.isNotEmpty()) {
                    fullWidth("channels") {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            NaoText("Chaînes", NaoType.Row)
                            LazyRow(
                                Modifier.fillMaxWidth().focusRestorer(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(state.channels) { i, c ->
                                    ChannelBubble(c, "sch/$i/${c.url}", onClick = { nav.push(Screen.Channel(c)) })
                                }
                            }
                        }
                    }
                }
                if (state.submitted.isNotEmpty()) {
                    fullWidth("title") {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            NaoText("Résultats", NaoType.Row)
                            NaoText("« ${state.submitted} »", NaoType.CardMeta.copy(fontSize = 10.sp))
                        }
                    }
                }
                when {
                    state.loading -> fullWidth("loading") { ShimmerRow(startPadding = 0.dp) }
                    state.error != null -> fullWidth("error") {
                        MessagePanel(
                            "La recherche a échoué",
                            Fmt.error(state.error!!),
                            actionLabel = "Réessayer",
                            onAction = { state.search(scope, state.submitted) },
                        )
                    }
                    state.submitted.isEmpty() -> fullWidth("hint") {
                        NaoText(
                            "Tape ta recherche puis appuie sur Entrée.",
                            NaoType.Body.copy(color = NaoColors.TextDim),
                        )
                    }
                    state.videos.isEmpty() && state.channels.isEmpty() -> fullWidth("none") {
                        NaoText("Aucun résultat.", NaoType.Body)
                    }
                }
            },
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(Color(0x12FFFFFF))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent.main.copy(alpha = 0.85f) else Color(0x29FFFFFF), shape)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NaoIcon("ic_search", Color.White, 16.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                NaoText("Vidéos, chaînes, musique…", NaoType.Title.copy(fontSize = 17.sp, color = NaoColors.TextDim))
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).remembered("field", focusRequester),
                singleLine = true,
                textStyle = NaoType.Title.copy(fontSize = 17.sp, color = Color.White),
                cursorBrush = SolidColor(accent.main),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
                interactionSource = interaction,
            )
        }
    }
}
