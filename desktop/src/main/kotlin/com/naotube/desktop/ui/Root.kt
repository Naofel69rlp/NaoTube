/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naotube.desktop.Assets
import com.naotube.desktop.Nao
import com.naotube.desktop.UpdateState
import com.naotube.desktop.ui.screens.ChannelScreen
import com.naotube.desktop.ui.screens.DetailScreen
import com.naotube.desktop.ui.screens.HomeScreen
import com.naotube.desktop.ui.screens.LibraryScreen
import com.naotube.desktop.ui.screens.PlayerScreen
import com.naotube.desktop.ui.screens.SearchScreen
import com.naotube.desktop.ui.screens.SettingsScreen
import com.naotube.desktop.ui.screens.SubscriptionsScreen

/** Lets screens (the player) switch the window to full screen. */
class WindowControls(
    val isFullscreen: () -> Boolean,
    val setFullscreen: (Boolean) -> Unit,
) {
    fun toggle() = setFullscreen(!isFullscreen())
}

val LocalWindowControls = staticCompositionLocalOf { WindowControls({ false }, {}) }

/** Desktop replacement for Android's back button: Échap, Retour arrière, bouton « précédent » de la souris. */
class BackDispatcher {
    val handlers = ArrayList<() -> Boolean>()
    fun dispatch(): Boolean = handlers.asReversed().any { it() }
}

val LocalBackDispatcher = staticCompositionLocalOf { BackDispatcher() }

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    val currentOnBack by rememberUpdatedState(onBack)
    val currentEnabled by rememberUpdatedState(enabled)
    DisposableEffect(dispatcher) {
        val handler: () -> Boolean = {
            if (currentEnabled) {
                currentOnBack()
                true
            } else {
                false
            }
        }
        dispatcher.handlers.add(handler)
        onDispose { dispatcher.handlers.remove(handler) }
    }
}

/** Width of the design in "dp": the window is scaled so the UI always looks like the mockup. */
private const val DESIGN_WIDTH = 960f

@Composable
fun NaoDesktopRoot(controls: WindowControls) {
    NaoTheme {
        val nav = remember { Navigator() }
        val back = remember { BackDispatcher() }
        val holder = rememberSaveableStateHolder()

        LaunchedEffect(Unit) {
            val last = Nao.settings.lastUpdateCheck.value
            if (System.currentTimeMillis() - last > 24 * 3600_000L) {
                Nao.settings.lastUpdateCheck.value = System.currentTimeMillis()
                Nao.updater.check()
            }
        }

        fun goBack() {
            if (!back.dispatch()) nav.back()
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(NaoColors.Background)) {
            val px = with(LocalDensity.current) { maxWidth.toPx() }
            val scaled = Density(density = (px / DESIGN_WIDTH).coerceAtLeast(0.5f), fontScale = 1f)
            val focusManager = LocalFocusManager.current
            CompositionLocalProvider(
                LocalDensity provides scaled,
                LocalNav provides nav,
                LocalBackDispatcher provides back,
                LocalWindowControls provides controls,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press && event.buttons.isBackPressed) goBack()
                                }
                            }
                        }
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when {
                                e.key == Key.DirectionLeft && e.isAltPressed -> { goBack(); true }
                                e.key == Key.Escape || e.key == Key.Backspace -> { goBack(); true }
                                e.key == Key.F11 -> { controls.toggle(); true }
                                // Arrow keys move the selection, like the TV remote.
                                e.key == Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                                e.key == Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                                e.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                e.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                else -> false
                            }
                        }
                ) {
                    AnimatedContent(
                        targetState = nav.top,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
                        label = "screens",
                    ) { entry ->
                        holder.SaveableStateProvider(entry.id) {
                            CompositionLocalProvider(LocalMemory provides entry.memory) {
                                Box(Modifier.fillMaxSize()) {
                                    ScreenContent(entry.screen)
                                    when {
                                        entry.screen.isTab -> NavRail(entry.screen)
                                        entry.screen !is Screen.Player -> BackButton(
                                            { goBack() },
                                            Modifier.padding(start = 16.dp, top = 18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen()
        Screen.Search -> SearchScreen()
        Screen.Subscriptions -> SubscriptionsScreen()
        Screen.Library -> LibraryScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.Detail -> DetailScreen(screen.video)
        is Screen.Channel -> ChannelScreen(screen.channel)
        is Screen.Player -> PlayerScreen(screen.video, screen.fromStart, screen.startMs)
    }
}

@Composable
private fun NavRail(current: Screen) {
    val nav = LocalNav.current
    val memory = LocalMemory.current
    val accent = LocalAccent.current
    val update by Nao.updater.state.collectAsState()
    Column(
        Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF2060607), Color(0x99060607), Color.Transparent)))
            .padding(top = 20.dp, bottom = 24.dp)
            // Right arrow: back to where the user was in the screen (results, rows…).
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && memory.restoreContentFocus()
            }
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Image(
            Assets.painter("logo_mark"),
            contentDescription = "NaoTube",
            modifier = Modifier.size(width = 50.dp, height = 34.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(22.dp))
        RailButton("ic_search", current == Screen.Search) { nav.openTab(Screen.Search) }
        RailButton("ic_home", current == Screen.Home) { nav.openTab(Screen.Home) }
        RailButton("ic_subscriptions", current == Screen.Subscriptions) { nav.openTab(Screen.Subscriptions) }
        RailButton("ic_library", current == Screen.Library) { nav.openTab(Screen.Library) }
        Spacer(Modifier.weight(1f))
        Box {
            RailButton("ic_settings", current == Screen.Settings) { nav.openTab(Screen.Settings) }
            if (update is UpdateState.Available) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent.main)
                )
            }
        }
    }
}

@Composable
private fun RailButton(icon: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(contentAlignment = Alignment.Center) {
        if (selected) {
            Box(
                Modifier
                    .offset(x = (-15).dp)
                    .width(2.5.dp)
                    .height(17.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.main)
            )
        }
        FocusSurface(
            onClick = onClick,
            modifier = Modifier.size(32.dp).then(if (selected) Modifier.remembered("rail-selected") else Modifier),
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.1f,
            glow = false,
            ring = false,
            color = if (selected) accent.main.copy(alpha = 0.16f) else Color.Transparent,
            focusedColor = Color.White,
        ) { focused ->
            val tint = when {
                focused -> Color(0xFF0B0B0D)
                selected -> accent.main
                else -> NaoColors.TextMuted
            }
            NaoIcon(icon, tint, 15.dp, Modifier.align(Alignment.Center))
        }
    }
}
