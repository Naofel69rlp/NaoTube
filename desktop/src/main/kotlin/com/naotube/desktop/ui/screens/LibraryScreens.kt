/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.desktop.Nao
import com.naotube.tv.core.Fmt
import com.naotube.desktop.ui.InitialFocus
import com.naotube.desktop.ui.Load
import com.naotube.desktop.ui.LocalMemory
import com.naotube.desktop.ui.LocalNav
import com.naotube.desktop.ui.Screen
import com.naotube.desktop.ui.ChannelBubble
import com.naotube.desktop.ui.Chip
import com.naotube.desktop.ui.MessagePanel
import com.naotube.desktop.ui.NaoText
import com.naotube.desktop.ui.PillButton
import com.naotube.desktop.ui.ShimmerRow
import com.naotube.desktop.ui.StatusCorner
import com.naotube.desktop.ui.VideoGrid
import com.naotube.desktop.ui.fullWidth
import com.naotube.desktop.ui.rememberLoader
import com.naotube.desktop.ui.remembered
import com.naotube.desktop.ui.LocalAccent
import com.naotube.desktop.ui.NaoColors
import com.naotube.desktop.ui.NaoType

@Composable
fun SubscriptionsScreen() {
    val app = Nao
    val nav = LocalNav.current
    val accent = LocalAccent.current
    val subs by app.library.subscriptions.collectAsState()
    val loader = rememberLoader("feed:${subs.size}") { app.repository.subscriptionFeed(subs, 120) }
    val first = remember { FocusRequester() }
    InitialFocus(first)

    Box(Modifier.fillMaxSize()) {
        StatusCorner(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 32.dp))
        if (subs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MessagePanel(
                    "Aucun abonnement pour l'instant",
                    "Ouvre une chaîne et appuie sur « S'abonner » : ses nouvelles vidéos arriveront ici. Tes abonnements restent sur ce PC, sans compte Google.",
                    actionLabel = "Rechercher une chaîne",
                    onAction = { nav.openTab(Screen.Search) },
                    focusRequester = first,
                )
            }
            return@Box
        }
        val videos = (loader.state as? Load.Ok)?.value.orEmpty()
        VideoGrid(
            gridKey = "feed",
            videos = videos,
            onOpen = { openVideo(nav, it) },
            modifier = Modifier.fillMaxSize(),
            topPadding = 32.dp,
            header = {
                fullWidth("t") { NaoText("ABONNEMENTS", NaoType.Overline, color = accent.text) }
                fullWidth("channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NaoText("Mes chaînes · ${subs.size}", NaoType.Row)
                        LazyRow(
                            Modifier.fillMaxWidth().focusRestorer().focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(subs, key = { _, c -> c.url }) { i, c ->
                                Box(if (i == 0) Modifier.remembered("first-ch", first) else Modifier) {
                                    ChannelBubble(c, "subs/${c.url}", onClick = { nav.push(Screen.Channel(c)) })
                                }
                            }
                        }
                    }
                }
                fullWidth("feedtitle") { NaoText("Dernières vidéos", NaoType.Row) }
                when (val s = loader.state) {
                    Load.Loading -> fullWidth("l") { ShimmerRow(startPadding = 0.dp) }
                    is Load.Failed -> fullWidth("e") {
                        MessagePanel("Impossible de charger le fil", Fmt.error(s.error), actionLabel = "Réessayer", onAction = { loader.reload() })
                    }
                    is Load.Ok -> if (s.value.isEmpty()) fullWidth("n") { NaoText("Rien de nouveau.", NaoType.Body) }
                }
            },
        )
    }
}

@Composable
fun LibraryScreen() {
    val app = Nao
    val nav = LocalNav.current
    val memory = LocalMemory.current
    val accent = LocalAccent.current
    val history by app.library.history.collectAsState()
    val later by app.library.watchLater.collectAsState()
    var tab by remember { mutableStateOf(memory.get("tab") { 0 }) }
    val first = remember { FocusRequester() }
    InitialFocus(first)

    val videos = when (tab) {
        0 -> history.filter { it.inProgress }.map { it.video }
        1 -> later
        else -> history.map { it.video }
    }
    Box(Modifier.fillMaxSize()) {
        StatusCorner(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 32.dp))
        VideoGrid(
            gridKey = "lib$tab",
            videos = videos,
            onOpen = { v -> if (tab == 0) nav.push(Screen.Player(v)) else openVideo(nav, v) },
            modifier = Modifier.fillMaxSize(),
            topPadding = 32.dp,
            header = {
                fullWidth("t") { NaoText("BIBLIOTHÈQUE", NaoType.Overline, color = accent.text) }
                fullWidth("tabs") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Reprendre", "Ma liste", "Historique").forEachIndexed { i, label ->
                            Box(if (i == tab) Modifier.remembered("tab-sel", first) else Modifier) {
                                Chip(label, { tab = i; memory.data["tab"] = i }, selected = i == tab)
                            }
                        }
                        if (tab == 2 && history.isNotEmpty()) {
                            PillButton("Effacer l'historique", { app.library.clearHistory() }, icon = "ic_trash", height = 28.dp)
                        }
                    }
                }
                if (videos.isEmpty()) {
                    fullWidth("empty") {
                        NaoText(
                            when (tab) {
                                0 -> "Aucune vidéo en cours. Celles que tu commences apparaîtront ici."
                                1 -> "Ta liste est vide. Utilise « Plus tard » sur une vidéo pour l'y ajouter."
                                else -> "Ton historique est vide."
                            },
                            NaoType.Body.copy(color = NaoColors.TextDim, fontSize = 12.sp),
                        )
                    }
                }
            },
        )
    }
}
