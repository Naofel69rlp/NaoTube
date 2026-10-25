/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

class SearchPage(
    val videos: List<VideoItem>,
    val channels: List<ChannelItem>,
    val nextPage: Page?,
    val handler: SearchQueryHandler,
)

class ChannelPage(
    val channel: ChannelItem,
    val banner: String?,
    val description: String?,
    val videos: List<VideoItem>,
    val nextPage: Page?,
    val videosTab: ListLinkHandler?,
)

class MorePage(val videos: List<VideoItem>, val nextPage: Page?)

/** Everything that talks to YouTube goes through here, always off the main thread. */
class YouTubeRepository {
    private val service: StreamingService = ServiceList.YouTube
    private val streamCache = ConcurrentHashMap<String, Pair<Long, StreamInfo>>()
    private val feedCache = ConcurrentHashMap<String, Pair<Long, List<VideoItem>>>()

    suspend fun kiosk(id: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = service.kioskList.getExtractorById(id, null)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem() }
            .filter { !it.isShort && it.url.isNotEmpty() }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else service.suggestionExtractor.suggestionList(query)
    }

    suspend fun search(query: String): SearchPage = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(service, handler)
        toSearchPage(info.relatedItems, info.nextPage, handler)
    }

    suspend fun searchMore(previous: SearchPage): SearchPage = withContext(Dispatchers.IO) {
        val page = previous.nextPage ?: return@withContext SearchPage(
            emptyList(), emptyList(), null, previous.handler
        )
        val more = SearchInfo.getMoreItems(service, previous.handler, page)
        toSearchPage(more.items, more.nextPage, previous.handler)
    }

    private fun toSearchPage(
        items: List<InfoItem>,
        next: Page?,
        handler: SearchQueryHandler,
    ): SearchPage {
        val videos = items.filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem() }
            .filter { !it.isShort && it.url.isNotEmpty() }
        val channels = items.filterIsInstance<ChannelInfoItem>().map { it.toChannelItem() }
        return SearchPage(videos, channels, next, handler)
    }

    /** Full video details, kept 20 minutes (stream URLs expire after a few hours). */
    suspend fun stream(url: String, forceRefresh: Boolean = false): StreamInfo =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = streamCache[url]
            if (!forceRefresh && cached != null && now - cached.first < 20 * 60_000) {
                return@withContext cached.second
            }
            val info = StreamInfo.getInfo(service, url)
            streamCache[url] = now to info
            info
        }

    fun cachedStream(url: String): StreamInfo? {
        val cached = streamCache[url] ?: return null
        return if (System.currentTimeMillis() - cached.first < 20 * 60_000) cached.second else null
    }

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(service, url)
        val channel = ChannelItem(
            url = info.url ?: url,
            name = info.name ?: "",
            avatar = info.avatars.bestUrl(176),
            subscriberCount = info.subscriberCount,
        )
        val videosTab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
        var videos = emptyList<VideoItem>()
        var next: Page? = null
        if (videosTab != null) {
            val tab = ChannelTabInfo.getInfo(service, videosTab)
            videos = tab.relatedItems.filterIsInstance<StreamInfoItem>()
                .map { it.toVideoItem().withChannel(channel) }
            next = tab.nextPage
        }
        ChannelPage(
            channel = channel,
            banner = info.banners.bestUrl(1280),
            description = info.description,
            videos = videos,
            nextPage = next,
            videosTab = videosTab,
        )
    }

    suspend fun channelMore(page: ChannelPage, next: Page): MorePage = withContext(Dispatchers.IO) {
        val tab = page.videosTab ?: return@withContext MorePage(emptyList(), null)
        val more = ChannelTabInfo.getMoreItems(service, tab, next)
        MorePage(
            more.items.filterIsInstance<StreamInfoItem>()
                .map { it.toVideoItem().withChannel(page.channel) },
            more.nextPage,
        )
    }

    /** Latest uploads of one channel (RSS based, fast). Cached 15 minutes. */
    suspend fun feed(channel: ChannelItem): List<VideoItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        feedCache[channel.url]?.let { (at, items) -> if (now - at < 15 * 60_000) return@withContext items }
        val info = FeedInfo.getInfo(service, channel.url)
        val items = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .map { it.toVideoItem().withChannel(channel) }
            .filter { it.url.isNotEmpty() }
        feedCache[channel.url] = now to items
        items
    }

    /** Newest videos across all subscriptions, newest first. */
    suspend fun subscriptionFeed(channels: List<ChannelItem>, limit: Int = 60): List<VideoItem> =
        coroutineScope {
            val gate = Semaphore(6)
            channels.take(80).map { channel ->
                async {
                    gate.withPermit { runCatching { feed(channel) }.getOrDefault(emptyList()) }
                }
            }.awaitAll()
                .flatten()
                .distinctBy { it.url }
                .sortedByDescending { it.uploadedAtMs ?: 0L }
                .take(limit)
        }

    private fun VideoItem.withChannel(channel: ChannelItem): VideoItem = copy(
        channelName = channelName.ifEmpty { channel.name },
        channelUrl = channelUrl.ifEmpty { channel.url },
        channelAvatar = channelAvatar ?: channel.avatar,
    )
}
