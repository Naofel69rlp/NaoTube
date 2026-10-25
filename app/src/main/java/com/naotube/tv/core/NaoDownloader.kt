/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Adapted from NewPipe's DownloaderImpl (GPL-3.0-or-later, NewPipe e.V.).
 */
package com.naotube.tv.core

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** HTTP bridge between NewPipeExtractor and OkHttp. */
class NaoDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val data = request.dataToSend()

        var body: RequestBody? = data?.toRequestBody()
        if (body == null && (method == "POST" || method == "PUT" || method == "PATCH")) {
            body = ByteArray(0).toRequestBody()
        }

        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val text = response.body.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString()
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
    }
}
