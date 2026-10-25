/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The request format sent to YouTube's video servers (POST, "&range=" and "&rn=" parameters,
 * user agent per client) follows NewPipe's YoutubeHttpDataSource (GPL-3.0-or-later, NewPipe e.V.).
 */
package com.naotube.desktop.player

import com.naotube.tv.core.NaoDownloader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A tiny HTTP server on 127.0.0.1 between VLC and YouTube. VLC asks for byte ranges with normal
 * GET requests; the proxy fetches them from YouTube in small pieces, the way YouTube expects.
 */
class StreamProxy(private val client: OkHttpClient) {

    private class Upstream(val url: String, val mime: String, @Volatile var length: Long, val youtube: Boolean)

    private val streams = ConcurrentHashMap<String, Upstream>()
    private val requestCounter = AtomicLong(0)
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    init {
        thread(name = "naotube-proxy", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "naotube-proxy-conn", isDaemon = true) { serve(socket) }
            }
        }
    }

    /** @return the local address VLC should open for this YouTube stream. */
    fun register(url: String, mime: String, length: Long, youtube: Boolean = true): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        streams[id] = Upstream(url, mime, length, youtube)
        return "http://127.0.0.1:$port/s/$id"
    }

    fun clear() = streams.clear()

    // ------------------------------------------------------------------ HTTP

    private fun serve(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 60_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                val id = path.removePrefix("/s/").substringBefore('?')
                val upstream = streams[id]
                if (upstream == null) {
                    respond(out, "404 Not Found", emptyMap())
                    return
                }
                handle(upstream, method, headers["range"], out)
            }
        }
    }

    private fun handle(up: Upstream, method: String, rangeHeader: String?, out: OutputStream) {
        if (up.length <= 0) up.length = probeLength(up) ?: -1
        val total = up.length
        if (total <= 0) {
            respond(out, "502 Bad Gateway", emptyMap())
            return
        }
        var start = 0L
        var end = total - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            if (a.isNotEmpty()) {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, total - 1)
            } else if (b.isNotEmpty()) {
                // "bytes=-N": the last N bytes
                start = (total - (b.toLongOrNull() ?: 0L)).coerceAtLeast(0)
            }
        }
        if (start >= total) {
            respond(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total"))
            return
        }
        val partial = rangeHeader != null
        val headers = linkedMapOf(
            "Content-Type" to up.mime,
            "Content-Length" to (end - start + 1).toString(),
            "Accept-Ranges" to "bytes",
        )
        if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
        respond(out, if (partial) "206 Partial Content" else "200 OK", headers)
        if (method.equals("HEAD", ignoreCase = true)) return

        var position = start
        val buffer = ByteArray(64 * 1024)
        while (position <= end) {
            val chunkEnd = minOf(position + CHUNK - 1, end)
            var attempt = 0
            while (true) {
                try {
                    fetch(up, position, chunkEnd).use { body ->
                        while (true) {
                            val read = body.read(buffer)
                            if (read < 0) break
                            try {
                                out.write(buffer, 0, read)
                            } catch (e: IOException) {
                                throw ClientGone() // VLC closed the connection (seek, stop…)
                            }
                            position += read
                        }
                    }
                    break
                } catch (e: ClientGone) {
                    return
                } catch (e: IOException) {
                    // Network hiccup: retry this piece a couple of times, then give up.
                    if (++attempt > 2) return
                    Thread.sleep(400L * attempt)
                }
            }
            if (position <= chunkEnd) return // YouTube sent less than asked: stop cleanly
            out.flush()
        }
    }

    private class ClientGone : IOException()

    /** Opens one piece of the stream; the caller reads it fully. */
    private fun fetch(up: Upstream, from: Long, to: Long): InputStream {
        val request = if (up.youtube) {
            youtubeRequest(up.url + "&range=$from-$to&rn=${requestCounter.incrementAndGet()}")
        } else {
            plainRequest(up.url, from, to)
        }
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            println("[proxy] upstream answered HTTP ${response.code}")
            throw IOException("YouTube a répondu ${response.code}")
        }
        val stream = response.body.byteStream()
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                runCatching { stream.close() }
                response.close()
            }
        }
    }

    private fun probeLength(up: Upstream): Long? = runCatching {
        val request = if (up.youtube) {
            youtubeRequest(up.url + "&range=0-0&rn=${requestCounter.incrementAndGet()}")
        } else {
            plainRequest(up.url, 0, 0)
        }
        client.newCall(request).execute().use { r ->
            val length = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: r.header("X-Content-Length")?.toLongOrNull()
            if (length == null) println("[proxy] could not get the stream size (HTTP ${r.code})")
            length
        }
    }.onFailure { println("[proxy] size request failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()

    private fun plainRequest(url: String, from: Long, to: Long): Request = Request.Builder()
        .url(url)
        .header("Range", "bytes=$from-$to")
        .header("Accept-Encoding", "identity")
        .header("User-Agent", NaoDownloader.USER_AGENT)
        .build()

    private fun youtubeRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .post(POST_BODY.toRequestBody())
            .header("Accept-Encoding", "identity")
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Referer", "https://www.youtube.com/")
        }
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NaoDownloader.USER_AGENT
        }
        return builder.header("User-Agent", userAgent).build()
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (!headers.containsKey("Content-Length") && !status.startsWith("2")) sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n\r\n")
        try {
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (e: IOException) {
            throw ClientGone()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    companion object {
        private const val CHUNK = 2L * 1024 * 1024
        private val POST_BODY = byteArrayOf(0x78, 0)
    }
}
