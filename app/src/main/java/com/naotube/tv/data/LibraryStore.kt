/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's own data, stored only on the TV: subscriptions, watch history (with resume
 * positions) and the "Ma liste" watch-later list.
 */
class LibraryStore(context: Context) {
    private val dir: File = context.filesDir
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val channelList = ListSerializer(ChannelItem.serializer())
    private val historyList = ListSerializer(HistoryEntry.serializer())
    private val videoList = ListSerializer(VideoItem.serializer())

    private val _subscriptions = MutableStateFlow(read("subscriptions.json", channelList))
    val subscriptions: StateFlow<List<ChannelItem>> = _subscriptions

    private val _history = MutableStateFlow(read("history.json", historyList))
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _watchLater = MutableStateFlow(read("watch_later.json", videoList))
    val watchLater: StateFlow<List<VideoItem>> = _watchLater

    // ------------------------------------------------------------ subscriptions

    fun isSubscribed(channelUrl: String): Boolean =
        _subscriptions.value.any { it.url == channelUrl }

    fun toggleSubscription(channel: ChannelItem) {
        if (channel.url.isEmpty()) return
        val current = _subscriptions.value
        val updated = if (current.any { it.url == channel.url }) {
            current.filterNot { it.url == channel.url }
        } else {
            listOf(channel) + current
        }
        _subscriptions.value = updated
        write("subscriptions.json", channelList, updated)
    }

    // ------------------------------------------------------------ history

    fun progressOf(url: String): HistoryEntry? = _history.value.firstOrNull { it.video.url == url }

    fun saveProgress(video: VideoItem, positionMs: Long, durationMs: Long) {
        if (video.url.isEmpty()) return
        val entry = HistoryEntry(
            video = video,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            watchedAt = System.currentTimeMillis(),
        )
        val updated = (listOf(entry) + _history.value.filterNot { it.video.url == video.url }).take(400)
        _history.value = updated
        write("history.json", historyList, updated)
    }

    fun removeFromHistory(url: String) {
        val updated = _history.value.filterNot { it.video.url == url }
        _history.value = updated
        write("history.json", historyList, updated)
    }

    fun clearHistory() {
        _history.value = emptyList()
        write("history.json", historyList, emptyList())
    }

    // ------------------------------------------------------------ watch later

    fun isInWatchLater(url: String): Boolean = _watchLater.value.any { it.url == url }

    fun toggleWatchLater(video: VideoItem) {
        val current = _watchLater.value
        val updated = if (current.any { it.url == video.url }) {
            current.filterNot { it.url == video.url }
        } else {
            listOf(video) + current
        }
        _watchLater.value = updated
        write("watch_later.json", videoList, updated)
    }

    // ------------------------------------------------------------ storage

    private fun <T> read(name: String, serializer: KSerializer<List<T>>): List<T> {
        val file = File(dir, name)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
    }

    private fun <T> write(name: String, serializer: KSerializer<List<T>>, value: List<T>) {
        scope.launch {
            writeLock.withLock {
                runCatching {
                    val tmp = File(dir, "$name.tmp")
                    tmp.writeText(json.encodeToString(serializer, value))
                    val target = File(dir, name)
                    if (!tmp.renameTo(target)) {
                        target.writeText(tmp.readText())
                        tmp.delete()
                    }
                }
            }
        }
    }
}
