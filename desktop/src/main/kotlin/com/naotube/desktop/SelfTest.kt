/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop

import com.naotube.desktop.player.VideoFrames
import com.naotube.desktop.player.Vlc
import kotlinx.coroutines.runBlocking
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `NaoTube.exe --selftest`: checks, without any window, that the bundled VLC loads and decodes
 * pictures through NaoTube's renderer, and that YouTube answers. Used by the GitHub build.
 */
object SelfTest {
    private const val SAMPLE =
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

    fun run(): Int {
        var ok = true
        Vlc.logLevel = uk.co.caprica.vlcj.log.LogLevel.WARNING
        println("[selftest] VLC folder: ${Vlc.bundledDir()?.absolutePath ?: "not found"}")
        val factory = Vlc.factory()
        if (factory == null) {
            println("[selftest] FAIL vlc: ${Vlc.error}")
            return 2
        }
        println("[selftest] OK vlc loaded: ${runCatching { factory.application().version() }.getOrDefault("?")}")

        val frames = VideoFrames()
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
        val playing = CountDownLatch(1)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
            override fun error(mediaPlayer: MediaPlayer) {
                println("[selftest] vlc reported an error while playing the sample")
            }
        })
        player.audio().setMute(true)
        // Same path as a YouTube video: picture and sound as two streams through the local proxy.
        val proxy = com.naotube.desktop.player.Proxy.instance
        val videoUrl = proxy.register(SAMPLE, "video/mp4", -1, youtube = false)
        val audioUrl = proxy.register(SAMPLE, "video/mp4", -1, youtube = false)
        println("[selftest] playing $videoUrl + slave $audioUrl")
        player.media().play(videoUrl, ":input-slave=$audioUrl")
        val started = playing.await(30, TimeUnit.SECONDS)
        Thread.sleep(6000)
        val pictures = frames.displayed.get()
        println("[selftest] sample playing=$started pictures=$pictures size=${frames.lastWidth}x${frames.lastHeight}")
        if (!started || pictures < 5) {
            println("[selftest] FAIL video decoding/rendering")
            ok = false
        } else {
            println("[selftest] OK video decoding/rendering")
        }
        runCatching { player.controls().stop() }
        runCatching { player.release() }

        val trending = runCatching { runBlocking { Nao.repository.kiosk("trending_music") } }
        trending.onSuccess { println("[selftest] OK youtube: ${it.size} trending videos") }
            .onFailure { println("[selftest] WARN youtube: ${it.javaClass.simpleName}: ${it.message}") }

        println(if (ok) "[selftest] PASSED" else "[selftest] FAILED")
        return if (ok) 0 else 1
    }
}
