/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui.screens

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.naotube.tv.NaoTubeApp
import com.naotube.tv.R
import com.naotube.tv.core.Fmt
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.SearchPage
import com.naotube.tv.data.VideoItem
import com.naotube.tv.ui.InitialFocus
import com.naotube.tv.ui.LocalMemory
import com.naotube.tv.ui.LocalNav
import com.naotube.tv.ui.Screen
import com.naotube.tv.ui.components.ChannelBubble
import com.naotube.tv.ui.components.Chip
import com.naotube.tv.ui.components.MessagePanel
import com.naotube.tv.ui.components.NaoIcon
import com.naotube.tv.ui.components.NaoText
import com.naotube.tv.ui.components.RoundIconButton
import com.naotube.tv.ui.components.ShimmerRow
import com.naotube.tv.ui.components.StatusCorner
import com.naotube.tv.ui.components.VideoGrid
import com.naotube.tv.ui.components.fullWidth
import com.naotube.tv.ui.remembered
import com.naotube.tv.ui.theme.LocalAccent
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoType
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
            suggestions = runCatching { NaoTubeApp.instance.repository.suggestions(q) }
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
                val p = NaoTubeApp.instance.repository.search(text)
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
            runCatching { NaoTubeApp.instance.repository.searchMore(p) }.onSuccess { next ->
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
    val scope = com.naotube.tv.ui.Loader.appScope
    val fieldFocus = remember { FocusRequester() }
    InitialFocus(fieldFocus)

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) state.search(scope, text)
    }

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
                                R.drawable.ic_mic,
                                {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Que veux-tu regarder ?")
                                    runCatching { voice.launch(intent) }
                                },
                                size = 44.dp,
                                iconSize = 17.dp,
                                filled = true,
                            )
                            RoundIconButton(
                                R.drawable.ic_search,
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
                                    Chip(s, { state.search(scope, s) }, icon = R.drawable.ic_search)
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
                            "Tape ta recherche avec la télécommande, ou appuie sur le micro pour parler.",
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
        NaoIcon(R.drawable.ic_search, Color.White, 16.dp)
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
