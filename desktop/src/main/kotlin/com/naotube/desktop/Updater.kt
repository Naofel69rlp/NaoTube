/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

data class UpdateInfo(val version: String, val url: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Looks for a newer NaoTube-Setup.exe in the GitHub releases (the same releases as the TV app),
 * downloads it and runs it: the installer replaces this version and starts NaoTube again.
 */
class Updater(private val client: OkHttpClient) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentVersion: String = BuildInfo.VERSION

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildInfo.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NaoTube-Windows")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub a répondu ${response.code}")
                val json = JSONObject(response.body.string())
                val version = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets")
                var url: String? = null
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").equals("NaoTube-Setup.exe", ignoreCase = true)) {
                            url = asset.optString("browser_download_url")
                            size = asset.optLong("size")
                        }
                    }
                }
                if (url.isNullOrEmpty() || !isNewer(version, currentVersion)) null
                else UpdateInfo(version, url, size)
            }
        }.fold(
            onSuccess = { info ->
                _state.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
                info
            },
            onFailure = {
                _state.value = UpdateState.Failed("Impossible de vérifier les mises à jour.")
                null
            },
        )
    }

    suspend fun downloadAndInstall(info: UpdateInfo) {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "NaoTube-update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "NaoTube-Setup-${info.version}.exe")
                val request = Request.Builder().url(info.url).header("User-Agent", "NaoTube-Windows").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var done = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                            }
                        }
                    }
                }
                target
            }.getOrNull()
        }
        if (file == null) {
            _state.value = UpdateState.Failed("Le téléchargement a échoué.")
            return
        }
        runCatching {
            ProcessBuilder(file.absolutePath, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART")
                .directory(file.parentFile)
                .start()
        }.onSuccess {
            // The installer closes the old version: leave cleanly so it can replace the files.
            exitProcess(0)
        }.onFailure {
            _state.value = UpdateState.Failed("Impossible de lancer l'installateur.")
        }
    }

    companion object {
        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
            val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
