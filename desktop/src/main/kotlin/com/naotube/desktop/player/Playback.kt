/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naotube.desktop.Nao
import com.naotube.tv.core.Fmt
import com.naotube.tv.core.NaoDownloader
import com.naotube.tv.data.SkipSegment
import com.naotube.tv.data.SponsorBlock
import com.naotube.tv.data.VideoItem
import com.naotube.tv.data.toVideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.GraphicsEnvironment
import java.io.File

object Proxy {
    val instance: StreamProxy by lazy { StreamProxy(Nao.httpClient) }
}

/** What will be played: chosen streams and the menus' content. */
class Choice(
    val video: VideoStream?,
    val audio: AudioStream?,
    val hlsUrl: String?,
    val qualities: List<Int>,
    val height: Int,
    val label: String,
    val subtitles: List<SubtitlesStream>,
    val subtitle: SubtitlesStream?,
) {
    val isLive: Boolean get() = hlsUrl != null
}

object StreamPicker {
    /** Physical height of the screen (2160 on a 4K monitor). */
    fun screenHeight(): Int = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .maxOf { it.displayMode.height }
    }.getOrDefault(1080)

    private fun family(codec: String?): String {
        val c = codec?.lowercase() ?: return "avc"
        return when {
            c.startsWith("av01") -> "av1"
            c.startsWith("vp9") || c.startsWith("vp09") -> "vp9"
            else -> "avc"
        }
    }

    fun qualityLabel(height: Int, fps: Int): String {
        if (height <= 0) return "Auto"
        val name = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            else -> "${height}p"
        }
        return if (fps > 30 && height < 1440) "$name$fps" else name
    }

    fun pick(
        info: StreamInfo,
        maxQuality: Int,
        codecPreference: String,
        subtitleMode: String,
        overrideHeight: Int? = null,
        overrideSubtitle: SubtitlesStream? = null,
        subtitlesOff: Boolean = false,
    ): Choice {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) {
            val hls = info.hlsUrl
            require(!hls.isNullOrEmpty()) { "Ce direct n'est pas lisible." }
            return Choice(null, null, hls, emptyList(), 0, "Direct", emptyList(), null)
        }

        // Software decoding of very large pictures is heavy: "auto" stops at 1440p.
        val screen = screenHeight()
        val limit = when {
            overrideHeight != null -> overrideHeight
            maxQuality > 0 -> maxQuality
            else -> minOf(screen, 1440)
        }

        val videoOnly = info.videoOnlyStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itagItem != null &&
                (it.format == MediaFormat.MPEG_4 || it.format == MediaFormat.WEBM)
        }
        val offered = videoOnly.map { it.height }.filter { it > 0 }.distinct().sortedDescending()

        fun score(v: VideoStream): Int {
            val fam = family(v.codec)
            if (codecPreference != "auto") return if (fam == codecPreference) 10 else 0
            // H.264 is the lightest to decode up to 1080p, VP9 above (H.264 stops at 1080p).
            return when (fam) {
                "avc" -> if (v.height <= 1080) 3 else 0
                "vp9" -> 2
                else -> 1
            }
        }

        val chosen = videoOnly
            .filter { it.height in 1..limit }
            .ifEmpty { videoOnly.sortedBy { it.height }.take(1) }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.fps }
                    .thenByDescending { score(it) }
            )
            .firstOrNull()

        val subtitles = info.subtitles.filter { it.isUrl && it.format == MediaFormat.VTT && !it.content.isNullOrEmpty() }
        val subtitle = when {
            subtitlesOff -> null
            overrideSubtitle != null -> overrideSubtitle
            subtitleMode == "fr" -> subtitles.firstOrNull { isFrench(it) && !it.isAutoGenerated }
                ?: subtitles.firstOrNull { isFrench(it) }
            subtitleMode == "original" -> subtitles.firstOrNull { !it.isAutoGenerated } ?: subtitles.firstOrNull()
            else -> null
        }

        if (chosen == null) {
            // Old-style stream with picture and sound together (360p) as a last resort.
            val muxed = info.videoStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .maxByOrNull { it.height }
                ?: error("Aucun flux vidéo lisible pour cette vidéo.")
            return Choice(muxed, null, null, emptyList(), muxed.height, qualityLabel(muxed.height, muxed.fps), subtitles, subtitle)
        }
        val audio = pickAudio(info.audioStreams) ?: error("Aucune piste audio disponible.")
        return Choice(chosen, audio, null, offered, chosen.height, qualityLabel(chosen.height, chosen.fps), subtitles, subtitle)
    }

    fun isFrench(s: SubtitlesStream): Boolean = s.languageTag?.lowercase()?.startsWith("fr") == true

    private fun pickAudio(list: List<AudioStream>): AudioStream? {
        val usable = list.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itagItem != null
        }
        val noDrc = usable.filter { it.itagItem?.isDrc != true }.ifEmpty { usable }
        val original = noDrc.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { noDrc }
        return original.maxByOrNull { it.averageBitrate }
    }
}

/**
 * One video being watched: fetches the streams, drives VLC, skips sponsors, remembers where
 * you stopped and chains to the next video.
 */
class PlaybackSession(
    private val scope: CoroutineScope,
    val video: VideoItem,
    private val startMs: Long?,
    private val onEnded: (next: VideoItem?) -> Unit,
) {
    val frames = VideoFrames()
    private val player: EmbeddedMediaPlayer?

    var info by mutableStateOf<StreamInfo?>(null)
    var choice by mutableStateOf<Choice?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var buffering by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var volume by mutableIntStateOf(Nao.settings.volume.value)
    var muted by mutableStateOf(false)
    var segments by mutableStateOf<List<SkipSegment>>(emptyList())
    var skipToast by mutableStateOf<String?>(null)
    private var skipToastAt = 0L

    private var refreshedOnce = false
    private var lastSave = 0L
    private val skipped = HashSet<String>()
    private var loadJob: Job? = null
    private var endedHandled = false
    private var pendingSubtitle: File? = null

    val fullVideo: VideoItem
        get() = info?.toVideoItem()?.let { it.copy(thumbnail = it.thumbnail ?: video.thumbnail) } ?: video

    init {
        val factory = Vlc.factory()
        player = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
        if (player == null) {
            error = "Le lecteur vidéo n'a pas pu démarrer : ${Vlc.error ?: "VLC introuvable"}."
            loading = false
        } else {
            player.videoSurface().set(factory!!.videoSurfaces().newVideoSurface(frames, frames, true))
            player.audio().setVolume(volume)
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = ui {
                    playing = true
                    loading = false
                    buffering = false
                    applyPendingSubtitle()
                }

                override fun paused(mediaPlayer: MediaPlayer) = ui { playing = false }

                override fun stopped(mediaPlayer: MediaPlayer) = ui { playing = false }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui {
                    buffering = newCache < 100f
                }

                override fun finished(mediaPlayer: MediaPlayer) = ui {
                    playing = false
                    if (!endedHandled && choice?.isLive != true) {
                        endedHandled = true
                        saveProgress(force = true, finished = true)
                        onEnded(nextVideo())
                    }
                }

                override fun error(mediaPlayer: MediaPlayer) = ui {
                    if (!refreshedOnce) {
                        // Stream addresses expire after a few hours: get fresh ones once.
                        refreshedOnce = true
                        load(force = true, keepPosition = position.takeIf { it > 0 })
                    } else {
                        loading = false
                        error = "La lecture a échoué. Vérifie ta connexion puis réessaie."
                    }
                }
            })
        }
    }

    private fun ui(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    fun load(
        force: Boolean = false,
        keepPosition: Long? = null,
        height: Int? = null,
        subtitle: SubtitlesStream? = null,
        subtitlesOff: Boolean = false,
    ) {
        val p = player ?: return
        loadJob?.cancel()
        error = null
        loading = true
        endedHandled = false
        loadJob = scope.launch {
            try {
                val s = Nao.settings
                val current = choice
                val streamInfo = withContext(Dispatchers.IO) { Nao.repository.stream(video.url, forceRefresh = force) }
                val picked = withContext(Dispatchers.Default) {
                    StreamPicker.pick(
                        streamInfo,
                        maxQuality = s.maxQuality.value,
                        codecPreference = s.codec.value,
                        subtitleMode = s.subtitles.value,
                        overrideHeight = height ?: current?.height?.takeIf { keepPosition != null && it > 0 },
                        overrideSubtitle = subtitle ?: current?.subtitle?.takeIf { keepPosition != null && !subtitlesOff },
                        subtitlesOff = subtitlesOff,
                    )
                }
                info = streamInfo
                choice = picked
                val start = keepPosition ?: initialPosition(streamInfo)
                pendingSubtitle = picked.subtitle?.let { downloadSubtitle(it) }
                val options = mutableListOf<String>()
                val mrl: String
                if (picked.hlsUrl != null) {
                    mrl = picked.hlsUrl
                    options += ":http-user-agent=${NaoDownloader.USER_AGENT}"
                } else {
                    val v = picked.video!!
                    val proxy = Proxy.instance
                    proxy.clear()
                    mrl = proxy.register(v.content, v.format?.mimeType ?: "video/mp4", v.itagItem?.contentLength ?: -1)
                    val a = picked.audio
                    if (a != null) {
                        val audioUrl = proxy.register(a.content, a.format?.mimeType ?: "audio/mp4", a.itagItem?.contentLength ?: -1)
                        options += ":input-slave=$audioUrl"
                    }
                    if (start > 0) options += ":start-time=${start / 1000.0}"
                }
                withContext(Dispatchers.IO) {
                    p.media().play(mrl, *options.toTypedArray())
                }
                if (speed != 1f) p.controls().setRate(speed)
                if (segments.isEmpty() && s.sponsorBlock.value && !picked.isLive) {
                    segments = Nao.sponsorBlock.segments(streamInfo.id ?: video.id, s.sponsorCategories.value)
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                loading = false
                error = (t as? IllegalStateException)?.message?.takeIf { it.isNotBlank() && t.cause == null }
                    ?: (t as? IllegalArgumentException)?.message
                    ?: Fmt.error(t)
            }
        }
    }

    private suspend fun downloadSubtitle(s: SubtitlesStream): File? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(s.content).header("User-Agent", NaoDownloader.USER_AGENT).build()
            Nao.httpClient.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val file = File.createTempFile("naotube-sub", ".vtt")
                file.deleteOnExit()
                file.writeBytes(r.body.bytes())
                file
            }
        }.getOrNull()
    }

    private fun applyPendingSubtitle() {
        val file = pendingSubtitle ?: return
        pendingSubtitle = null
        runCatching { player?.subpictures()?.setSubTitleFile(file) }
    }

    private fun initialPosition(i: StreamInfo): Long {
        if (startMs != null) return startMs
        val entry = Nao.library.progressOf(video.url)
        if (entry != null && entry.inProgress) return (entry.positionMs - 3000).coerceAtLeast(0)
        if (i.startPosition > 0) return i.startPosition * 1000
        return 0L
    }

    /** Called every 250 ms from the screen. */
    fun tick() {
        val p = player ?: return
        val t = p.status().time()
        if (t >= 0) position = t
        val len = p.status().length()
        if (len > 0) duration = len
        if (playing) {
            val seg = segments.firstOrNull { position >= it.startMs && position < it.endMs - 500 }
            if (seg != null && seg.uuid !in skipped) {
                skipped += seg.uuid
                p.controls().setTime(seg.endMs)
                skipToast = "${SponsorBlock.label(seg.category)} passé · ${Fmt.duration((seg.endMs - seg.startMs) / 1000)}"
                skipToastAt = System.currentTimeMillis()
            }
            saveProgress()
        }
        if (skipToast != null && System.currentTimeMillis() - skipToastAt > 3500) skipToast = null
    }

    fun saveProgress(force: Boolean = false, finished: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 10_000) return
        if (choice?.isLive == true || info == null) return
        lastSave = now
        val d = duration.takeIf { it > 0 } ?: return
        val pos = if (finished) d else position
        if (pos < 5_000 && !finished) return
        Nao.library.saveProgress(fullVideo, pos, d)
    }

    fun nextVideo(): VideoItem? {
        if (!Nao.settings.autoplay.value) return null
        val history = Nao.library.history.value
        return info?.relatedItems?.filterIsInstance<StreamInfoItem>()
            ?.map { it.toVideoItem() }
            ?.filter { !it.isShort && !it.isLive && it.url.isNotEmpty() }
            ?.firstOrNull { v -> history.none { it.video.url == v.url && !it.inProgress } }
    }

    fun togglePlay() {
        val p = player ?: return
        if (!p.status().isPlaying && p.status().state() == uk.co.caprica.vlcj.player.base.State.ENDED) {
            load(keepPosition = 0)
            return
        }
        p.controls().pause()
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        val d = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (position + ms).coerceIn(0, d - 1000)
        p.controls().setTime(target)
        position = target
    }

    fun seekTo(fraction: Float) {
        val p = player ?: return
        if (duration <= 0) return
        val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
        p.controls().setTime(target)
        position = target
    }

    fun changeSpeed(value: Float) {
        speed = value
        player?.controls()?.setRate(value)
    }

    fun changeVolume(delta: Int) = setVolumeTo(volume + delta)

    fun setVolumeTo(value: Int) {
        volume = value.coerceIn(0, 150)
        Nao.settings.volume.value = volume
        player?.audio()?.setVolume(volume)
        if (muted && volume > 0) toggleMute()
    }

    fun toggleMute() {
        muted = !muted
        player?.audio()?.setMute(muted)
    }

    fun release() {
        saveProgress(force = true)
        loadJob?.cancel()
        val p = player ?: return
        runCatching { p.controls().stop() }
        runCatching { p.release() }
        frames.release()
    }
}
