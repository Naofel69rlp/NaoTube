/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.security.MessageDigest

data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val uuid: String,
)

/**
 * SponsorBlock (https://sponsor.ajay.app), the community database of sponsored segments.
 * Uses the privacy-preserving hash-prefix endpoint: the video ID itself is never sent.
 */
class SponsorBlock(private val client: OkHttpClient) {

    suspend fun segments(videoId: String, categories: Set<String>): List<SkipSegment> =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank() || categories.isEmpty()) return@withContext emptyList()
            val prefix = sha256(videoId).take(4)
            val cats = categories.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=" +
                URLEncoder.encode(cats, "UTF-8") +
                "&actionTypes=" + URLEncoder.encode("[\"skip\"]", "UTF-8")
            val request = Request.Builder().url(url).header("User-Agent", "NaoTube (Android TV)").build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<SkipSegment>()
                    parse(response.body.string(), videoId)
                }
            }.getOrDefault(emptyList())
        }

    private fun parse(body: String, videoId: String): List<SkipSegment> {
        val out = mutableListOf<SkipSegment>()
        val videos = JSONArray(body)
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            if (video.optString("videoID") != videoId) continue
            val segments = video.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val s = segments.optJSONObject(j) ?: continue
                if (s.optString("actionType", "skip") != "skip") continue
                val bounds = s.optJSONArray("segment") ?: continue
                val start = (bounds.optDouble(0, 0.0) * 1000).toLong()
                val end = (bounds.optDouble(1, 0.0) * 1000).toLong()
                if (end - start < 1000) continue
                out += SkipSegment(start, end, s.optString("category"), s.optString("UUID"))
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val CATEGORIES: List<Pair<String, String>> = listOf(
            "sponsor" to "Sponsors",
            "selfpromo" to "Autopromotion",
            "interaction" to "Rappels d'abonnement",
            "intro" to "Intros",
            "outro" to "Génériques de fin",
            "preview" to "Aperçus et récaps",
            "music_offtopic" to "Hors-sujet (musique)",
            "filler" to "Remplissage",
        )

        fun label(category: String): String =
            CATEGORIES.firstOrNull { it.first == category }?.second ?: "Segment"
    }
}
