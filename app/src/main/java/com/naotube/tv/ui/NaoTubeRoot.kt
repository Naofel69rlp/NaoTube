/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.naotube.tv.NaoTubeApp
import com.naotube.tv.R
import com.naotube.tv.data.UpdateState
import com.naotube.tv.ui.components.FocusSurface
import com.naotube.tv.ui.components.NaoIcon
import com.naotube.tv.ui.screens.ChannelScreen
import com.naotube.tv.ui.screens.DetailScreen
import com.naotube.tv.ui.screens.HomeScreen
import com.naotube.tv.ui.screens.LibraryScreen
import com.naotube.tv.ui.screens.PlayerScreen
import com.naotube.tv.ui.screens.SearchScreen
import com.naotube.tv.ui.screens.SettingsScreen
import com.naotube.tv.ui.screens.SubscriptionsScreen
import com.naotube.tv.ui.theme.LocalAccent
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoTheme

@Composable
fun NaoTubeRoot(onExit: () -> Unit) {
    NaoTheme {
        val nav = remember { Navigator() }
        val holder = rememberSaveableStateHolder()
        val app = NaoTubeApp.instance

        // Daily silent update check.
        LaunchedEffect(Unit) {
            val last = app.settings.lastUpdateCheck.value
            if (System.currentTimeMillis() - last > 24 * 3600_000L) {
                app.settings.lastUpdateCheck.value = System.currentTimeMillis()
                app.updater.check()
            }
        }

        BackHandler {
            if (!nav.back()) onExit()
        }

        CompositionLocalProvider(LocalNav provides nav) {
            Box(Modifier.fillMaxSize().background(NaoColors.Background)) {
                val entry = nav.top
                AnimatedContent(
                    targetState = entry,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "screens",
                ) { e ->
                    holder.SaveableStateProvider(e.id) {
                        CompositionLocalProvider(LocalMemory provides e.memory) {
                            Box(Modifier.fillMaxSize()) {
                                ScreenContent(e.screen)
                                if (e.screen.isTab) NavRail(e.screen)
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

/** Left navigation rail of the mockup: logo, five icons, accent bar on the current tab. */
@Composable
private fun NavRail(current: Screen) {
    val nav = LocalNav.current
    val accent = LocalAccent.current
    val update by NaoTubeApp.instance.updater.state.collectAsState()
    Column(
        Modifier
            .width(62.dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xF2060607), Color(0x99060607), Color.Transparent)
                )
            )
            .padding(top = 20.dp, bottom = 24.dp)
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Image(
            painterResource(R.drawable.logo_mark),
            contentDescription = "NaoTube",
            modifier = Modifier.size(width = 50.dp, height = 34.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(22.dp))
        RailButton(R.drawable.ic_search, current == Screen.Search) { nav.openTab(Screen.Search) }
        RailButton(R.drawable.ic_home, current == Screen.Home) { nav.openTab(Screen.Home) }
        RailButton(R.drawable.ic_subscriptions, current == Screen.Subscriptions) {
            nav.openTab(Screen.Subscriptions)
        }
        RailButton(R.drawable.ic_library, current == Screen.Library) { nav.openTab(Screen.Library) }
        Spacer(Modifier.weight(1f))
        Box {
            RailButton(R.drawable.ic_settings, current == Screen.Settings) { nav.openTab(Screen.Settings) }
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
private fun RailButton(icon: Int, selected: Boolean, onClick: () -> Unit) {
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
            modifier = Modifier
                .size(32.dp)
                .then(if (selected) Modifier.remembered("rail-selected") else Modifier),
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
