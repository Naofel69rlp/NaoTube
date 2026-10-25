/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image

/**
 * Loads the assets shared with the TV app (copied into the classpath by the build):
 * the line icons (Android vector XML), the logos and the fonts.
 */
object Assets {
    private fun bytes(path: String): ByteArray =
        (Assets::class.java.getResourceAsStream("/$path") ?: error("Missing resource $path"))
            .use { it.readBytes() }

    fun font(name: String): ByteArray = bytes("fonts/$name.ttf")

    private val images = HashMap<String, ImageBitmap>()

    fun image(name: String): ImageBitmap = synchronized(images) {
        images.getOrPut(name) { Image.makeFromEncoded(bytes("images/$name.png")).toComposeImageBitmap() }
    }

    fun painter(name: String) = BitmapPainter(image(name))

    private val icons = HashMap<String, ImageVector>()

    /** The same 24×24 line icons as on the TV, drawn white (tinted when displayed). */
    fun icon(name: String): ImageVector = synchronized(icons) {
        icons.getOrPut(name) { parseVector(name, String(bytes("icons/$name.xml"))) }
    }

    private val pathRegex = Regex("<path(.*?)/>", RegexOption.DOT_MATCHES_ALL)
    private fun attr(block: String, name: String): String? =
        Regex("android:$name=\"([^\"]*)\"").find(block)?.groupValues?.get(1)

    private fun parseVector(name: String, xml: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        pathRegex.findAll(xml).forEach { match ->
            val block = match.groupValues[1]
            val data = attr(block, "pathData") ?: return@forEach
            val stroked = attr(block, "strokeColor") != null
            val width = attr(block, "strokeWidth")?.toFloatOrNull() ?: 2f
            if (stroked) {
                builder.addPath(
                    pathData = addPathNodes(data),
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                builder.addPath(
                    pathData = addPathNodes(data),
                    fill = SolidColor(Color.White),
                )
            }
        }
        return builder.build()
    }
}
