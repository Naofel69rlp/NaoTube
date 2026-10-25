/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Stream selection and media source building follow NewPipe's PlaybackResolver /
 * VideoPlaybackResolver (GPL-3.0-or-later, NewPipe e.V.).
 */
package com.naotube.tv.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

class Resolved(
    val source: MediaSource,
    /** Heights offered in the quality menu, highest first. */
    val qualities: List<Int>,
    val selectedHeight: Int,
    val label: String,
    val subtitles: List<SubtitlesStream>,
    val selectedSubtitle: SubtitlesStream?,
    val isLive: Boolean,
)

object PlayerEngine {

    fun newPlayer(context: Context): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                // Side-loaded WebVTT subtitles are decoded by the renderer itself.
                out.add(TextRenderer(output, outputLooper).apply {
                    experimentalSetLegacyDecodingEnabled(true)
                })
            }
        }.setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    // ------------------------------------------------------------ device capabilities

    private val hwCache = HashMap<String, Boolean>()

    private fun hasDecoder(mime: String, hardwareOnly: Boolean): Boolean {
        val key = "$mime/$hardwareOnly"
        return hwCache.getOrPut(key) {
            runCatching {
                val infos = MediaCodecUtil.getDecoderInfos(mime, false, false)
                if (hardwareOnly) infos.any { it.hardwareAccelerated } else infos.isNotEmpty()
            }.getOrDefault(false)
        }
    }

    /** Height of the physical screen (a 4K TV reports 2160 even if the UI runs at 1080p). */
    fun displayHeight(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val modeHeight = if (Build.VERSION.SDK_INT >= 23) {
            display.supportedModes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
        } else {
            0
        }
        val metrics = context.resources.displayMetrics
        return maxOf(modeHeight, minOf(metrics.widthPixels, metrics.heightPixels), 720)
    }

    private fun codecFamily(codec: String?): String {
        val c = codec?.lowercase() ?: return "avc"
        return when {
            c.startsWith("av01") -> "av1"
            c.startsWith("vp9") || c.startsWith("vp09") -> "vp9"
            else -> "avc"
        }
    }

    private fun codecSupported(family: String): Boolean = when (family) {
        "av1" -> hasDecoder(MimeTypes.VIDEO_AV1, hardwareOnly = true)
        "vp9" -> hasDecoder(MimeTypes.VIDEO_VP9, hardwareOnly = false)
        else -> true
    }

    private fun codecScore(family: String, preference: String): Int {
        if (preference == family) return 10
        return when (family) {
            "av1" -> 3
            "vp9" -> if (hasDecoder(MimeTypes.VIDEO_VP9, true)) 2 else 0
            else -> 1
        }
    }

    // ------------------------------------------------------------ resolving

    /**
     * Picks the best video + audio streams for this TV and builds the media source.
     * Must run off the main thread (the OTF manifest creator does network requests).
     */
    fun resolve(
        context: Context,
        client: OkHttpClient,
        info: StreamInfo,
        maxQuality: Int,
        codecPreference: String,
        subtitleMode: String,
        overrideHeight: Int? = null,
        overrideSubtitle: SubtitlesStream? = null,
        subtitlesOff: Boolean = false,
    ): Resolved {
        val ytHls = YoutubeDataSource.Factory(client, false, false)
        val ytDash = YoutubeDataSource.Factory(client, true, true)
        val ytProgressive = YoutubeDataSource.Factory(client, false, true)

        val live = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) {
            val hls = info.hlsUrl
            require(!hls.isNullOrEmpty()) { "Ce direct n'est pas lisible." }
            val source = HlsMediaSource.Factory(ytHls)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(hls)
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build()
                        )
                        .build()
                )
            return Resolved(source, emptyList(), 0, "Direct", emptyList(), null, true)
        }

        val screenLimit = displayHeight(context)
        val limit = when {
            overrideHeight != null -> overrideHeight
            maxQuality > 0 -> minOf(maxQuality, screenLimit)
            else -> screenLimit
        }

        val videoOnly = info.videoOnlyStreams
            .filter { it.itagItem != null && usable(it) }
            .filter { it.format == MediaFormat.MPEG_4 || it.format == MediaFormat.WEBM }
            .filter { codecSupported(codecFamily(it.codec)) }
        val offered = videoOnly.map { it.height }.filter { it > 0 }.distinct().sortedDescending()

        val chosenVideo: VideoStream? = videoOnly
            .filter { it.height in 1..limit }
            .ifEmpty { videoOnly.sortedBy { it.height }.take(1) }
            .sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.fps }
                    .thenByDescending { codecScore(codecFamily(it.codec), codecPreference) }
            )
            .firstOrNull()

        val sources = ArrayList<MediaSource>()
        var label: String
        val height: Int

        if (chosenVideo != null) {
            sources += dashSource(chosenVideo, info, ytDash)
            val audio = pickAudio(info.audioStreams)
                ?: error("Aucune piste audio disponible.")
            sources += dashSource(audio, info, ytDash)
            height = chosenVideo.height
            label = qualityLabel(height, chosenVideo.fps)
        } else {
            // Old fashioned muxed stream (360p) as a last resort.
            val muxed = info.videoStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .maxByOrNull { it.height }
                ?: error("Aucun flux vidéo lisible pour cette vidéo.")
            sources += ProgressiveMediaSource.Factory(ytProgressive)
                .createMediaSource(MediaItem.fromUri(muxed.content))
            height = muxed.height
            label = qualityLabel(height, muxed.fps)
        }

        val subtitles = info.subtitles.filter {
            it.isUrl && it.format == MediaFormat.VTT && !it.content.isNullOrEmpty()
        }
        val subtitle = when {
            subtitlesOff -> null
            overrideSubtitle != null -> overrideSubtitle
            subtitleMode == "fr" -> subtitles.firstOrNull { isFrench(it) && !it.isAutoGenerated }
                ?: subtitles.firstOrNull { isFrench(it) }
            subtitleMode == "original" -> subtitles.firstOrNull { !it.isAutoGenerated }
                ?: subtitles.firstOrNull()
            else -> null
        }
        if (subtitle != null) {
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.content))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(subtitle.languageTag)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .build()
            sources += SingleSampleMediaSource.Factory(ytProgressive)
                .createMediaSource(config, C.TIME_UNSET)
        }

        val source = if (sources.size == 1) sources[0] else MergingMediaSource(true, *sources.toTypedArray())
        if (offered.isEmpty()) label = label.ifEmpty { "Auto" }
        return Resolved(source, offered, height, label, subtitles, subtitle, false)
    }

    fun isFrench(s: SubtitlesStream): Boolean =
        s.languageTag?.lowercase()?.startsWith("fr") == true

    fun qualityLabel(height: Int, fps: Int): String {
        if (height <= 0) return "Auto"
        val name = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            else -> "${height}p"
        }
        return if (fps > 30 && height < 1440) "$name$fps" else name
    }

    private fun usable(s: Stream): Boolean =
        !s.content.isNullOrEmpty() &&
            (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || s.deliveryMethod == DeliveryMethod.DASH)

    private fun pickAudio(list: List<AudioStream>): AudioStream? {
        val usableList = list.filter { it.itagItem != null && usable(it) && it.itagItem?.isDrc != true }
            .ifEmpty { list.filter { it.itagItem != null && usable(it) } }
        val original = usableList.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { usableList }
        return original.maxByOrNull { it.averageBitrate }
    }

    private fun dashSource(stream: Stream, info: StreamInfo, factory: YoutubeDataSource.Factory): MediaSource {
        val itag = stream.itagItem ?: error("Flux incomplet.")
        val manifest = if (stream.deliveryMethod == DeliveryMethod.DASH) {
            YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag, info.duration)
        } else {
            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                stream.content, itag, info.duration
            )
        }
        val uri = Uri.parse(stream.content)
        val parsed = DashManifestParser().parse(uri, manifest.byteInputStream())
        return DashMediaSource.Factory(factory)
            .createMediaSource(parsed, MediaItem.Builder().setUri(uri).build())
    }
}
