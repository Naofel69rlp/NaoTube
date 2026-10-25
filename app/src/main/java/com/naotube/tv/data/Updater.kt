/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.naotube.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val version: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks the GitHub releases of the repository and installs newer builds of NaoTube. */
class Updater(private val context: Context, private val client: OkHttpClient) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NaoTube")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub a répondu ${response.code}")
                val json = JSONObject(response.body.string())
                val version = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            size = asset.optLong("size")
                            break
                        }
                    }
                }
                if (apkUrl.isNullOrEmpty() || !isNewer(version, currentVersion)) {
                    null
                } else {
                    UpdateInfo(version, apkUrl, size)
                }
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

    /** Downloads the APK, then hands it to Android's installer. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "NaoTube-${info.version}.apk")
                val request = Request.Builder().url(info.apkUrl).header("User-Agent", "NaoTube").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) {
                                    _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                                }
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
        _state.value = UpdateState.Available(info)
        install(file)
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // Android asks once for the permission to install apps from NaoTube.
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            _state.value = UpdateState.Failed(
                "Autorise NaoTube à installer des applis, puis relance la mise à jour."
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            _state.value = UpdateState.Failed("Impossible d'ouvrir l'installateur Android.")
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
