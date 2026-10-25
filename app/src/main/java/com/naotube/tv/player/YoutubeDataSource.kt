/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Behaviour ported from NewPipe's YoutubeHttpDataSource (GPL-3.0-or-later, NewPipe e.V.):
 * YouTube's video servers expect POST requests, a request counter (rn) and, for DASH chunks,
 * the byte range as a URL parameter instead of a Range header.
 */
package com.naotube.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.naotube.tv.core.NaoDownloader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

@UnstableApi
class YoutubeDataSource(
    private val client: OkHttpClient,
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean,
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0
    private var opened = false
    private var requestNumber: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0
        bytesToRead = C.LENGTH_UNSET.toLong()
        transferInitializing(dataSpec)

        val position = dataSpec.position
        val length = dataSpec.length
        var url = dataSpec.uri.toString()
        val isVideoPlayback = dataSpec.uri.path?.startsWith("/videoplayback") == true

        if (isVideoPlayback && rnParameterEnabled && !url.contains("&rn=")) {
            url += "&rn=$requestNumber"
            requestNumber++
        }
        var usingRangeParameter = false
        if (isVideoPlayback && rangeParameterEnabled) {
            val range = rangeParameter(position, length)
            if (range != null) {
                url += range
                usingRangeParameter = true
            }
        }

        val builder = Request.Builder().url(url)
        dataSpec.httpRequestHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (!usingRangeParameter) {
            rangeHeader(position, length)?.let { builder.header("Range", it) }
        }
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com/")
        }
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NaoDownloader.USER_AGENT
        }
        builder.header("User-Agent", userAgent)
        if (isVideoPlayback) {
            builder.post(POST_BODY.toRequestBody())
        } else {
            builder.get()
        }

        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        response = resp

        if (!resp.isSuccessful) {
            val code = resp.code
            val message = resp.message
            val headers = resp.headers.toMultimap()
            val errorBody = runCatching { resp.body.bytes() }.getOrDefault(ByteArray(0))
            closeConnection()
            throw HttpDataSource.InvalidResponseCodeException(
                code, message, null, headers, dataSpec, errorBody
            )
        }

        val body = resp.body
        input = body.byteStream()

        // A server that ignored our Range header answers 200 with the whole file.
        val toSkip = if (resp.code == 200 && !usingRangeParameter && position > 0) position else 0L
        bytesToRead = if (length != C.LENGTH_UNSET.toLong()) {
            length
        } else {
            val contentLength = body.contentLength()
            if (contentLength >= 0) contentLength - toSkip else C.LENGTH_UNSET.toLong()
        }
        if (toSkip > 0) skipFully(toSkip, dataSpec)

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var toRead = length
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            toRead = min(length.toLong(), remaining).toInt()
        }
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val read = try {
            stream.read(buffer, offset, toRead)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec ?: DataSpec(Uri.EMPTY),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        closeConnection()
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }

    private fun closeConnection() {
        try {
            input?.close()
        } catch (_: IOException) {
        }
        input = null
        response?.close()
        response = null
    }

    private fun skipFully(count: Long, spec: DataSpec) {
        val stream = input ?: return
        val scratch = ByteArray(16 * 1024)
        var left = count
        while (left > 0) {
            val read = stream.read(scratch, 0, min(left, scratch.size.toLong()).toInt())
            if (read < 0) {
                throw HttpDataSource.HttpDataSourceException(
                    spec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            left -= read
        }
    }

    private fun rangeParameter(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
        val end = if (length != C.LENGTH_UNSET.toLong()) (position + length - 1).toString() else ""
        return "&range=$position-$end"
    }

    private fun rangeHeader(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
        val end = if (length != C.LENGTH_UNSET.toLong()) (position + length - 1).toString() else ""
        return "bytes=$position-$end"
    }

    class Factory(
        private val client: OkHttpClient,
        private val rangeParameterEnabled: Boolean,
        private val rnParameterEnabled: Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            YoutubeDataSource(client, rangeParameterEnabled, rnParameterEnabled)
    }

    private companion object {
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
