/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.naotube.tv.NaoTubeApp
import com.naotube.tv.R

/** Fixed palette (dark only: this is a TV app). */
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

val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

/**
 * Type scale. Sizes are the mockup's pixel sizes divided by two: a 1080p TV renders the UI at
 * 960 × 540 dp, so the screens look exactly like the 1920 × 1080 mockup.
 */
object NaoType {
    val Hero = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 34.sp,
        lineHeight = 35.sp, letterSpacing = (-0.02).em, color = Color.White,
    )
    val Title = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
        lineHeight = 24.sp, color = NaoColors.Text,
    )
    val Row = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp,
        lineHeight = 19.sp, color = NaoColors.Text,
    )
    val Overline = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
        letterSpacing = 0.18.em, color = Color(0xFFFF5A5A),
    )
    val Button = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
        lineHeight = 16.sp, color = Color.White,
    )
    val Body = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 18.sp, color = NaoColors.Body,
    )
    val BodyStrong = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.5.sp,
        lineHeight = 15.sp, color = NaoColors.Text,
    )
    val Meta = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 11.sp,
        lineHeight = 15.sp, color = NaoColors.TextMuted,
    )
    val CardTitle = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
        lineHeight = 13.5.sp, color = NaoColors.CardTitle,
    )
    val CardMeta = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 9.sp,
        lineHeight = 12.sp, color = NaoColors.TextDim,
    )
    val Badge = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 8.sp,
        lineHeight = 10.sp, color = Color.White,
    )
    val Clock = TextStyle(
        fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NaoColors.Text,
    )
}

@Composable
fun NaoTheme(content: @Composable () -> Unit) {
    val accentKey by NaoTubeApp.instance.settings.accent.flow.collectAsState()
    CompositionLocalProvider(LocalAccent provides accentFor(accentKey), content = content)
}
