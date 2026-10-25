/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.data

import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@Serializable
data class VideoItem(
    val url: String,
    val id: String,
    val title: String,
    val channelName: String = "",
    val channelUrl: String = "",
    val channelAvatar: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = -1,
    val viewCount: Long = -1,
    val uploadedText: String? = null,
    val uploadedAtMs: Long? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val description: String? = null,
) {
    /** Sharper picture for big backdrops (may not exist for every video). */
    val hiResThumbnail: String
        get() = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
}

@Serializable
data class ChannelItem(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val subscriberCount: Long = -1,
)

@Serializable
data class HistoryEntry(
    val video: VideoItem,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Started but not finished: shown in "Reprendre". */
    val inProgress: Boolean
        get() = positionMs > 15_000 && progress < 0.94f
}

/** A titled row of videos on a screen. */
data class Section(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val videos: List<VideoItem>,
)

// ----------------------------------------------------------------- mapping

fun videoIdOf(url: String): String {
    val v = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.get(1)
    if (v != null) return v
    val short = Regex("(?:youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{6,})").find(url)
    return short?.groupValues?.get(1) ?: url.substringAfterLast('/').substringBefore('?')
}

fun List<Image>.bestUrl(targetWidth: Int): String? {
    val usable = filter { !it.url.isNullOrBlank() }
    if (usable.isEmpty()) return null
    val known = usable.filter { it.width > 0 }
    if (known.isEmpty()) return usable.last().url
    val pick = known.filter { it.width >= targetWidth }.minByOrNull { it.width }
        ?: known.maxByOrNull { it.width }
    return (pick ?: usable.last()).url
}

private fun isLiveType(type: StreamType?): Boolean =
    type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM

fun StreamInfoItem.toVideoItem(): VideoItem {
    val link = url ?: ""
    return VideoItem(
        url = link,
        id = videoIdOf(link),
        title = name ?: "",
        channelName = uploaderName ?: "",
        channelUrl = uploaderUrl ?: "",
        channelAvatar = uploaderAvatars.bestUrl(96),
        thumbnail = thumbnails.bestUrl(640),
        durationSec = duration,
        viewCount = viewCount,
        uploadedText = textualUploadDate,
        uploadedAtMs = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull(),
        isLive = isLiveType(streamType),
        isShort = isShortFormContent,
        description = shortDescription,
    )
}

fun StreamInfo.toVideoItem(): VideoItem {
    val link = url ?: ""
    return VideoItem(
        url = link,
        id = id ?: videoIdOf(link),
        title = name ?: "",
        channelName = uploaderName ?: "",
        channelUrl = uploaderUrl ?: "",
        channelAvatar = uploaderAvatars.bestUrl(96),
        thumbnail = thumbnails.bestUrl(640),
        durationSec = duration,
        viewCount = viewCount,
        uploadedText = textualUploadDate,
        uploadedAtMs = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull(),
        isLive = isLiveType(streamType),
    )
}

fun ChannelInfoItem.toChannelItem(): ChannelItem = ChannelItem(
    url = url ?: "",
    name = name ?: "",
    avatar = thumbnails.bestUrl(176),
    subscriberCount = subscriberCount,
)
