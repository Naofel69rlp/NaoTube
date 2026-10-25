/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.naotube.tv.R
import com.naotube.tv.ui.theme.LocalAccent
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoType
import com.naotube.tv.ui.theme.Outfit
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- text & icons

@Composable
fun NaoText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    var finalStyle = style
    if (color != Color.Unspecified) finalStyle = finalStyle.copy(color = color)
    if (align != null) finalStyle = finalStyle.copy(textAlign = align)
    BasicText(
        text = text,
        modifier = modifier,
        style = finalStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun NaoIcon(@DrawableRes icon: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

// ---------------------------------------------------------------- focus

/**
 * The basic TV building block: a clickable surface that grows, gets a white ring and glows in
 * the accent colour when the remote's focus lands on it.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.06f,
    glow: Boolean = true,
    ring: Boolean = true,
    color: Color = Color.Transparent,
    focusedColor: Color = color,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "focus",
    )
    val accent = LocalAccent.current.main
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = 1f + (focusedScale - 1f) * progress
                scaleX = s
                scaleY = s
            }
            .then(
                if (glow && progress > 0.01f) {
                    Modifier.dropShadow(
                        shape,
                        Shadow(radius = 22.dp, color = accent, alpha = 0.55f * progress),
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(lerp(color, focusedColor, progress), shape)
            .then(
                if (ring && progress > 0.01f) {
                    Modifier.border(2.dp, Color.White.copy(alpha = progress), shape)
                } else {
                    Modifier
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { onFocusChange?.invoke(it.isFocused) }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        content = { content(focused) },
    )
}

// ---------------------------------------------------------------- buttons

/** Rounded "pill" button of the mockup: red primary, glass secondary (white when focused). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    height: Dp = 36.dp,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    progress: Float? = null,
) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(height / 2)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = shape,
        focusedScale = 1.05f,
        glow = primary,
        ring = primary,
        color = if (primary) accent else NaoColors.Glass,
        focusedColor = if (primary) accent else Color.White,
        focusRequester = focusRequester,
        onFocusChange = onFocusChange,
    ) { focused ->
        val content = if (primary || !focused) Color.White else Color(0xFF0B0B0D)
        if (!primary && !focused) {
            Box(Modifier.matchParentSize().border(1.dp, NaoColors.Outline, shape))
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = if (icon != null) 15.dp else 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) NaoIcon(icon, content, 14.dp)
            NaoText(text, NaoType.Button, color = content, maxLines = 1)
        }
        if (progress != null && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(2.5.dp)
                    .background(Color.White.copy(alpha = 0.85f))
            )
        }
    }
}

/** Round icon-only button (player controls, like…). */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 16.dp,
    filled: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val accent = LocalAccent.current.main
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        focusedScale = 1.08f,
        glow = filled,
        ring = filled,
        color = if (filled) Color.White else NaoColors.Glass,
        focusedColor = Color.White,
        focusRequester = focusRequester,
        onFocusChange = onFocusChange,
    ) { focused ->
        val tint = if (filled || focused) Color(0xFF0B0B0D) else Color.White
        NaoIcon(icon, tint, iconSize, Modifier.align(Alignment.Center))
        if (filled && focused) {
            Box(Modifier.matchParentSize().border(2.5.dp, accent.copy(alpha = 0.55f), CircleShape))
        }
    }
}

/** Small selectable chip (search suggestions, filters, settings values). */
@Composable
fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @DrawableRes icon: Int? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(28.dp),
        shape = shape,
        focusedScale = 1.05f,
        glow = false,
        ring = false,
        color = if (selected) Color.White.copy(alpha = 0.9f) else NaoColors.Glass,
        focusedColor = Color.White,
        focusRequester = focusRequester,
        onFocusChange = onFocusChange,
    ) { focused ->
        val tint = if (focused || selected) Color(0xFF0B0B0D) else Color(0xFFE6E6EA)
        if (!focused && !selected) {
            Box(Modifier.matchParentSize().border(1.dp, NaoColors.Outline, shape))
        }
        Row(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) NaoIcon(icon, tint, 10.dp)
            NaoText(
                text,
                NaoType.Button.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------- small pieces

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, background: Color = NaoColors.Glass) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .height(17.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, Color(0x24FFFFFF), shape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        NaoText(
            text,
            NaoType.Badge.copy(fontSize = 8.5.sp, letterSpacing = 0.04.sp),
            color = NaoColors.Text,
            maxLines = 1,
        )
    }
}

@Composable
fun DurationBadge(text: String, modifier: Modifier = Modifier, live: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (live) NaoColors.Live else Color(0xB8000000))
            .padding(horizontal = 4.5.dp, vertical = 2.dp),
    ) {
        NaoText(text, NaoType.Badge, maxLines = 1)
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val accent = LocalAccent.current.main
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0x38FFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(accent)
        )
    }
}

/** Shimmering placeholder while content loads. */
@Composable
fun Shimmer(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(9.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    Box(
        modifier
            .clip(shape)
            .background(NaoColors.Surface)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color(0x14FFFFFF), Color.Transparent),
                    start = Offset(x * 600f, 0f),
                    end = Offset(x * 600f + 300f, 300f),
                )
            )
    )
}

/**
 * Network picture with a dark placeholder; [fallbackUrl] is tried if the first URL fails
 * (YouTube's "maxresdefault" pictures don't exist for every video).
 */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    placeholder: Color = NaoColors.Surface,
) {
    var current by remember(url, fallbackUrl) { mutableStateOf(url ?: fallbackUrl) }
    Box(modifier.background(placeholder)) {
        val model = current
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = alignment,
                onError = {
                    if (fallbackUrl != null && current != fallbackUrl) current = fallbackUrl
                },
            )
        }
    }
}

/** Round channel picture, or its initial on a tinted disc. */
@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF3A1416)),
        contentAlignment = Alignment.Center,
    ) {
        NaoText(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            TextStyle(
                fontFamily = Outfit,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.45f).sp,
                color = Color(0xFFFFC9C9),
            ),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = null,
            )
        }
    }
}

/** "Sans pub · SponsorBlock" pill + clock, top right of the main screens. */
@Composable
fun StatusCorner(modifier: Modifier = Modifier, showBadge: Boolean = true) {
    var now by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = clockText()
        }
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showBadge) {
            val shape = RoundedCornerShape(11.dp)
            Row(
                Modifier
                    .height(22.dp)
                    .clip(shape)
                    .background(NaoColors.Glass)
                    .border(1.dp, Color(0x1FFFFFFF), shape)
                    .padding(start = 7.dp, end = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                NaoIcon(R.drawable.ic_shield, Color(0xFFE6E6EA), 11.dp)
                NaoText(
                    "Sans pub · SponsorBlock",
                    NaoType.Meta.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFE6E6EA),
                    maxLines = 1,
                )
            }
        }
        NaoText(now, NaoType.Clock)
    }
}

private fun clockText(): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())

/** Centered message with an optional retry button. */
@Composable
fun MessagePanel(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NaoText(title, NaoType.Title, align = TextAlign.Center)
        if (!message.isNullOrBlank()) {
            NaoText(
                message,
                NaoType.Body,
                modifier = Modifier.width(420.dp),
                align = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(6.dp))
            PillButton(
                text = actionLabel,
                onClick = onAction,
                icon = R.drawable.ic_refresh,
                primary = true,
                focusRequester = focusRequester,
            )
        }
    }
}

/** Little verified tick next to channel names. */
@Composable
fun VerifiedTick(modifier: Modifier = Modifier, size: Dp = 11.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(NaoColors.TextMuted),
        contentAlignment = Alignment.Center,
    ) {
        NaoIcon(R.drawable.ic_check, NaoColors.Background, size * 0.7f)
    }
}
