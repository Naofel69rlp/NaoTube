/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naotube.desktop.Nao
import com.naotube.desktop.Pref
import com.naotube.tv.data.SponsorBlock
import com.naotube.desktop.UpdateState
import com.naotube.desktop.ui.InitialFocus
import com.naotube.desktop.ui.Loader
import com.naotube.desktop.ui.FocusSurface
import com.naotube.desktop.ui.NaoIcon
import com.naotube.desktop.ui.NaoText
import com.naotube.desktop.ui.StatusCorner
import com.naotube.desktop.ui.remembered
import com.naotube.desktop.ui.LocalAccent
import com.naotube.desktop.ui.NaoColors
import com.naotube.desktop.ui.NaoType
import kotlinx.coroutines.launch

private fun <T> Pref<T>.cycle(values: List<T>) {
    val i = values.indexOf(value)
    value = values[(i + 1) % values.size]
}

@Composable
fun SettingsScreen() {
    val app = Nao
    val s = app.settings
    val accent = LocalAccent.current
    val first = remember { FocusRequester() }
    InitialFocus(first)

    val quality by s.maxQuality.flow.collectAsState()
    val codec by s.codec.flow.collectAsState()
    val autoplay by s.autoplay.flow.collectAsState()
    val subtitles by s.subtitles.flow.collectAsState()
    val sponsor by s.sponsorBlock.flow.collectAsState()
    val cats by s.sponsorCategories.flow.collectAsState()
    val country by s.contentCountry.flow.collectAsState()
    val accentKey by s.accent.flow.collectAsState()
    val update by app.updater.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        StatusCorner(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 32.dp))
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = 86.dp, top = 32.dp, bottom = 48.dp, end = 300.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item { NaoText("RÉGLAGES", NaoType.Overline, color = accent.text) }
            item { NaoText("Lecture", NaoType.Row, Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            item {
                SettingRow(
                    "ic_tv", "Qualité maximale",
                    if (quality == 0) "Automatique (selon ton écran)" else PlayerLabel(quality),
                    Modifier.remembered("q", first),
                ) { s.maxQuality.cycle(listOf(0, 2160, 1440, 1080, 720, 480)) }
            }
            item {
                SettingRow(
                    "ic_sparkle", "Codec vidéo",
                    when (codec) { "avc" -> "H.264 (le plus compatible)"; "vp9" -> "VP9"; "av1" -> "AV1"; else -> "Automatique" },
                ) { s.codec.cycle(listOf("auto", "avc", "vp9", "av1")) }
            }
            item {
                SettingRow("ic_skip_next", "Lecture automatique", if (autoplay) "Activée" else "Désactivée") {
                    s.autoplay.value = !autoplay
                }
            }
            item {
                SettingRow(
                    "ic_cc", "Sous-titres",
                    when (subtitles) { "fr" -> "Français si disponibles"; "original" -> "Langue de la vidéo"; else -> "Désactivés" },
                ) { s.subtitles.cycle(listOf("off", "fr", "original")) }
            }
            item { NaoText("SponsorBlock", NaoType.Row, Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            item {
                SettingRow("ic_shield", "Passer les segments automatiquement", if (sponsor) "Activé" else "Désactivé") {
                    s.sponsorBlock.value = !sponsor
                }
            }
            if (sponsor) {
                SponsorBlock.CATEGORIES.forEach { (key, label) ->
                    item(key = key) {
                        val on = key in cats
                        SettingRow(null, label, if (on) "Passé" else "Conservé", checked = on) {
                            s.sponsorCategories.value = if (on) cats - key else cats + key
                        }
                    }
                }
            }
            item { NaoText("Contenu et apparence", NaoType.Row, Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            item {
                val countries = listOf("FR" to "France", "BE" to "Belgique", "CH" to "Suisse", "CA" to "Canada", "MA" to "Maroc", "DZ" to "Algérie", "TN" to "Tunisie", "US" to "États-Unis")
                SettingRow("ic_globe", "Pays des tendances", countries.firstOrNull { it.first == country }?.second ?: country) {
                    val next = countries[(countries.indexOfFirst { it.first == country } + 1) % countries.size].first
                    s.contentCountry.value = next
                    app.applyContentCountry(next)
                }
            }
            item {
                SettingRow(
                    "ic_palette", "Couleur d'accent",
                    when (accentKey) { "gold" -> "Or"; "blue" -> "Bleu"; "violet" -> "Violet"; else -> "Rouge NaoTube" },
                ) { s.accent.cycle(listOf("red", "gold", "blue", "violet")) }
            }
            item {
                val fullscreen by s.startFullscreen.flow.collectAsState()
                SettingRow("ic_tv", "Démarrer en plein écran", if (fullscreen) "Oui" else "Non") {
                    s.startFullscreen.value = !fullscreen
                }
            }
            item { NaoText("Application", NaoType.Row, Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            item {
                val value = when (val u = update) {
                    UpdateState.Idle -> "Version ${app.updater.currentVersion} · vérifier"
                    UpdateState.Checking -> "Vérification…"
                    UpdateState.UpToDate -> "À jour (${app.updater.currentVersion})"
                    is UpdateState.Available -> "Version ${u.info.version} disponible · installer"
                    is UpdateState.Downloading -> "Téléchargement… ${(u.progress * 100).toInt()} %"
                    is UpdateState.Failed -> u.message
                }
                SettingRow("ic_download", "Mises à jour", value) {
                    Loader.appScope.launch {
                        when (val u = update) {
                            is UpdateState.Available -> app.updater.downloadAndInstall(u.info)
                            is UpdateState.Downloading, UpdateState.Checking -> Unit
                            else -> app.updater.check()
                        }
                    }
                }
            }
            item {
                SettingRow("ic_trash", "Effacer l'historique", "Garde tes abonnements et ta liste") {
                    app.library.clearHistory()
                }
            }
            item {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NaoText("NaoTube ${app.updater.currentVersion} — logiciel libre (GPL-3.0)", NaoType.CardMeta.copy(fontSize = 10.sp))
                    NaoText(
                        "Moteur vidéo : NewPipe Extractor · Segments : SponsorBlock (sponsor.ajay.app). Tes données restent sur ce PC.",
                        NaoType.CardMeta.copy(fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

private fun PlayerLabel(h: Int) = if (h >= 2160) "4K (2160p)" else "${h}p"

@Composable
private fun SettingRow(
    icon: String?,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier.width(560.dp).height(44.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        glow = false,
        ring = false,
        color = Color(0x0AFFFFFF),
        focusedColor = Color.White,
    ) { focused ->
        val main = if (focused) Color(0xFF0B0B0D) else Color.White
        val sub = if (focused) Color(0xFF4A4A52) else NaoColors.TextMuted
        Row(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (icon != null) NaoIcon(icon, if (focused) main else accent.main, 16.dp)
            else Box(Modifier.width(16.dp))
            NaoText(title, NaoType.Button.copy(fontSize = 12.5.sp, color = main), Modifier.weight(1f), maxLines = 1)
            NaoText(value, NaoType.Meta.copy(fontSize = 11.sp, color = sub), maxLines = 1)
            if (checked != null) {
                Box(
                    Modifier.width(16.dp).height(16.dp)
                        .border(1.5.dp, if (checked) accent.main else sub, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) NaoIcon("ic_check", if (focused) main else accent.main, 11.dp)
                }
            }
        }
    }
}
