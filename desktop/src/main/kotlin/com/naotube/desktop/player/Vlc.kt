/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil
import com.naotube.tv.core.NaoDownloader
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.log.LogLevel
import uk.co.caprica.vlcj.log.NativeLog
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.EventQueue
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** libVLC, shipped inside the app (app\resources\vlc), loaded once. */
object Vlc {
    @Volatile
    var error: String? = null
        private set

    private var factoryInstance: MediaPlayerFactory? = null
    private var nativeLog: NativeLog? = null

    /** libVLC messages at this level and above go to naotube.log. */
    var logLevel: LogLevel = LogLevel.ERROR

    /** Folder holding libvlc.dll, libvlccore.dll and plugins\. */
    fun bundledDir(): File? {
        val candidates = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
            File(System.getProperty("user.dir"), "resources/windows/vlc"),
            File(System.getProperty("user.dir"), "desktop/resources/windows/vlc"),
        )
        return candidates.firstOrNull { File(it, "libvlc.dll").exists() }
    }

    @Synchronized
    fun factory(): MediaPlayerFactory? {
        factoryInstance?.let { return it }
        if (error != null) return null
        return try {
            val dir = bundledDir()
            if (dir != null) {
                val previous = System.getProperty("jna.library.path")
                System.setProperty(
                    "jna.library.path",
                    if (previous.isNullOrBlank()) dir.absolutePath else dir.absolutePath + File.pathSeparator + previous,
                )
            }
            val found = NativeDiscovery().discover()
            if (!found) error("Le lecteur VLC intégré est introuvable.")
            MediaPlayerFactory(
                "--quiet",
                "--no-video-title-show",
                "--no-snapshot-preview",
                "--no-osd",
                "--no-stats",
                "--no-sub-autodetect-file",
                "--network-caching=1500",
                "--http-reconnect",
            ).also { factory ->
                factoryInstance = factory
                runCatching { factory.application().setUserAgent("NaoTube", NaoDownloader.USER_AGENT) }
                runCatching {
                    nativeLog = factory.application().newLog().apply {
                        setLevel(logLevel)
                        addLogListener { level, module, _, _, _, _, _, message ->
                            println("[vlc ${level.name.lowercase()}] $module: $message")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            null
        }
    }
}

/**
 * Receives the decoded pictures from VLC and turns them into a Compose image. VLC writes into
 * the hidden buffer while the window draws the visible one; they are swapped on the UI thread.
 */
class VideoFrames : BufferFormatCallback, RenderCallback {
    private class Buffer(val width: Int, val height: Int) {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
        }
        val pixels: ByteBuffer = BufferUtil.getByteBufferFromPointer(
            bitmap.peekPixels()!!.addr,
            width * height * 4,
        )
        val image: ImageBitmap = bitmap.asComposeImageBitmap()
        fun close() = bitmap.close()
    }

    @Volatile
    private var front: Buffer? = null

    @Volatile
    private var back: Buffer? = null
    private val pending = AtomicBoolean(false)

    /** Incremented on every new picture: reading it in a draw block redraws the video. */
    var frame by mutableLongStateOf(0L)
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set

    val image: ImageBitmap? get() = front?.image

    /** Pictures received from VLC (used by the self-test). */
    val displayed = AtomicLong()

    @Volatile
    var lastWidth = 0
        private set

    @Volatile
    var lastHeight = 0
        private set

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        val w = sourceWidth.coerceAtLeast(2)
        val h = sourceHeight.coerceAtLeast(2)
        lastWidth = w
        lastHeight = h
        val oldFront = front
        val oldBack = back
        back = Buffer(w, h)
        val newFront = Buffer(w, h)
        EventQueue.invokeLater {
            front = newFront
            videoWidth = w
            videoHeight = h
            oldFront?.close()
            oldBack?.close()
        }
        return RV32BufferFormat(w, h)
    }

    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {}

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}

    override fun lock(mediaPlayer: MediaPlayer) {}

    override fun unlock(mediaPlayer: MediaPlayer) {}

    override fun display(
        mediaPlayer: MediaPlayer,
        nativeBuffers: Array<out ByteBuffer>,
        bufferFormat: BufferFormat,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        displayed.incrementAndGet()
        // The window has not shown the previous picture yet: drop this one rather than queue up.
        if (!pending.compareAndSet(false, true)) return
        val target = back
        if (target == null || target.width != bufferFormat.width || target.height != bufferFormat.height) {
            pending.set(false)
            return
        }
        val src = nativeBuffers[0].duplicate()
        src.rewind()
        val dst = target.pixels.duplicate()
        dst.clear()
        val count = minOf(src.remaining(), dst.remaining())
        src.limit(src.position() + count)
        dst.put(src)
        target.bitmap.notifyPixelsChanged()
        EventQueue.invokeLater {
            val shown = front
            // Skip pictures of a buffer replaced in the meantime (new video size).
            if (shown != null && back === target && shown.width == target.width && shown.height == target.height) {
                front = target
                back = shown
            }
            frame++
            pending.set(false)
        }
    }

    fun release() {
        EventQueue.invokeLater {
            front?.close()
            back?.close()
            front = null
            back = null
        }
    }
}
