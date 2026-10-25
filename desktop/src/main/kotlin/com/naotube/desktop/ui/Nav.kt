/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.naotube.tv.data.ChannelItem
import com.naotube.tv.data.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Subscriptions : Screen
    data object Library : Screen
    data object Settings : Screen
    data class Detail(val video: VideoItem) : Screen
    data class Channel(val channel: ChannelItem) : Screen
    data class Player(val video: VideoItem, val fromStart: Boolean = false, val startMs: Long? = null) : Screen
}

val Screen.isTab: Boolean
    get() = this is Screen.Home || this is Screen.Search || this is Screen.Subscriptions ||
        this is Screen.Library || this is Screen.Settings

/** Remembers, per screen instance, loaded data and which element had the focus. */
class ScreenMemory {
    var focusedKey: String? = null

    /** Last focused element of the screen itself (not the navigation rail). */
    var contentKey: String? = null
    val requesters = HashMap<String, FocusRequester>()
    val data = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, init: () -> T): T = data.getOrPut(key) { init() } as T

    /** Gives the focus back to the element focused before leaving the screen. */
    fun restoreFocus(): Boolean {
        val req = focusedKey?.let { requesters[it] } ?: return false
        return runCatching { req.requestFocus() }.getOrDefault(false)
    }

    /** From the navigation rail: goes back to the last focused element of the screen. */
    fun restoreContentFocus(): Boolean {
        val req = contentKey?.let { requesters[it] } ?: return false
        return runCatching { req.requestFocus() }.getOrDefault(false)
    }

    /** Focuses the first element whose key is in [keys] that can take the focus. */
    fun focusFirst(keys: List<String>): Boolean = keys.any { k ->
        val req = requesters[k] ?: return@any false
        runCatching { req.requestFocus() }.getOrDefault(false)
    }
}

class Entry(val screen: Screen, val id: Long) {
    val memory = ScreenMemory()
}

class Navigator {
    private var nextId = 0L
    val stack = mutableStateListOf(Entry(Screen.Home, nextId++))
    val top: Entry get() = stack.last()

    fun push(screen: Screen) {
        // Opening the player from the player (next video) replaces it.
        if (screen is Screen.Player && top.screen is Screen.Player) stack.removeAt(stack.lastIndex)
        stack.add(Entry(screen, nextId++))
    }

    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = Entry(screen, nextId++)
    }

    fun openTab(screen: Screen) {
        if (top.screen == screen && stack.size == 1) return
        stack.clear()
        stack.add(Entry(screen, nextId++))
    }

    /** @return false when there is nothing to go back to. */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (top.screen != Screen.Home) {
            openTab(Screen.Home)
            return true
        }
        return false
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }
val LocalMemory = staticCompositionLocalOf { ScreenMemory() }

/** Registers an element so the screen can give it the focus back later. */
@Composable
fun Modifier.remembered(key: String, requester: FocusRequester = remember { FocusRequester() }): Modifier {
    val memory = LocalMemory.current
    DisposableEffect(key, requester) {
        memory.requesters[key] = requester
        onDispose { if (memory.requesters[key] === requester) memory.requesters.remove(key) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged {
            if (it.isFocused) {
                memory.focusedKey = key
                if (!key.startsWith("rail")) memory.contentKey = key
            }
        }
}

/** On entering a screen: restore the previous focus, or focus [fallback]. */
@Composable
fun InitialFocus(fallback: FocusRequester, ready: Boolean = true) {
    val memory = LocalMemory.current
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        repeat(6) {
            delay(60)
            if (memory.restoreFocus()) return@LaunchedEffect
            if (runCatching { fallback.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

// ---------------------------------------------------------------- async loading

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** A value loaded in the background, kept in the screen memory so "back" doesn't reload it. */
class Loader<T>(private val block: suspend () -> T) {
    var state by mutableStateOf<Load<T>>(Load.Loading)
        private set
    private var job: Job? = null

    fun start(force: Boolean = false) {
        if (!force && (state is Load.Ok || job?.isActive == true)) return
        state = Load.Loading
        job = appScope.launch {
            var result: Load<T> = Load.Loading
            for (attempt in 0..2) {
                result = try {
                    Load.Ok(block())
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Load.Failed(t)
                }
                if (result is Load.Ok) break
                // The network or YouTube's first answer is often not ready right after start-up.
                delay(1500L * (attempt + 1))
            }
            state = result
        }
    }

    fun reload() = start(force = true)

    companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

@Composable
fun <T> rememberLoader(key: String, block: suspend () -> T): Loader<T> {
    val memory = LocalMemory.current
    val loader = remember(key) { memory.get("loader:$key") { Loader(block) } }
    LaunchedEffect(loader) { loader.start() }
    return loader
}
