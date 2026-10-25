/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.naotube.desktop.Assets
import com.naotube.desktop.Nao

/** Same palette as the TV app. */
object NaoColors {
    val Background = Color(0xFF060607)
    val Surface = Color(0xFF141418)
    val SurfaceHigh = Color(0xFF1C1C22)
    val Text = Color(0xFFF4F4F6)
    val TextMuted = Color(0xFFB4B4BC)
    val TextDim = Color(0xFF8A8A94)
    val Body = Color(0xFFC9C9D1)
    val CardTitle = Color(0xFF9C9CA6)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x24FFFFFF)
    val Outline = Color(0x29FFFFFF)
    val SponsorGreen = Color(0xFF3DDC97)
    val Live = Color(0xFFFF2D2D)
}

data class Accent(val main: Color, val text: Color)

fun accentFor(key: String): Accent = when (key) {
    "gold" -> Accent(Color(0xFFD4AF37), Color(0xFFE8C766))
    "blue" -> Accent(Color(0xFF3D8BFF), Color(0xFF7FB2FF))
    "violet" -> Accent(Color(0xFF9B5CFF), Color(0xFFC2A0FF))
    else -> Accent(Color(0xFFFF2D2D), Color(0xFFFF5A5A))
}

val LocalAccent = staticCompositionLocalOf { accentFor("red") }

private fun family(name: String) = FontFamily(
    Font("$name-regular", Assets.font("${name}_regular"), FontWeight.Normal),
    Font("$name-medium", Assets.font("${name}_medium"), FontWeight.Medium),
    Font("$name-semibold", Assets.font("${name}_semibold"), FontWeight.SemiBold),
    Font("$name-bold", Assets.font("${name}_bold"), FontWeight.Bold),
)

val Outfit: FontFamily by lazy { family("outfit") }
val Manrope: FontFamily by lazy { family("manrope") }

/**
 * Type scale of the mockup. The whole window is scaled so that it is always 960 "dp" wide, like
 * the TV, so these sizes give the same proportions as the 1920 × 1080 design at any window size.
 */
object NaoType {
    val Hero by lazy {
        TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 35.sp, letterSpacing = (-0.02).em, color = Color.White)
    }
    val Title by lazy {
        TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, color = NaoColors.Text)
    }
    val Row by lazy {
        TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 19.sp, color = NaoColors.Text)
    }
    val Overline by lazy {
        TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.18.em, color = Color(0xFFFF5A5A))
    }
    val Button by lazy {
        TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 16.sp, color = Color.White)
    }
    val Body by lazy {
        TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, color = NaoColors.Body)
    }
    val BodyStrong by lazy {
        TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, lineHeight = 15.sp, color = NaoColors.Text)
    }
    val Meta by lazy {
        TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, color = NaoColors.TextMuted)
    }
    val CardTitle by lazy {
        TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 13.5.sp, color = NaoColors.CardTitle)
    }
    val CardMeta by lazy {
        TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 9.sp, lineHeight = 12.sp, color = NaoColors.TextDim)
    }
    val Badge by lazy {
        TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 8.sp, lineHeight = 10.sp, color = Color.White)
    }
    val Clock by lazy {
        TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NaoColors.Text)
    }
}

@Composable
fun NaoTheme(content: @Composable () -> Unit) {
    val accentKey by Nao.settings.accent.flow.collectAsState()
    CompositionLocalProvider(LocalAccent provides accentFor(accentKey), content = content)
}
