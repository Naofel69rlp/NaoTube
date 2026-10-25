/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties

/** Where NaoTube keeps its data: %APPDATA%\NaoTube on Windows. */
object AppDirs {
    val data: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".config")
        File(base, "NaoTube").apply { mkdirs() }
    }
}

/** One persisted setting, observable from Compose through [flow]. */
class Pref<T>(initial: T, private val save: (T) -> Unit) {
    private val state = MutableStateFlow(initial)
    val flow: StateFlow<T> = state

    var value: T
        get() = state.value
        set(newValue) {
            state.value = newValue
            save(newValue)
        }
}

/** Same settings as the TV app, stored in a small properties file. */
class AppSettings(private val file: File = File(AppDirs.data, "settings.properties")) {
    private val props = Properties().apply {
        if (file.exists()) runCatching { file.inputStream().use { load(it) } }
    }

    @Synchronized
    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { props.store(it, "NaoTube") }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun intPref(key: String, def: Int) =
        Pref(props.getProperty(key)?.toIntOrNull() ?: def) { put(key, it.toString()) }

    private fun boolPref(key: String, def: Boolean) =
        Pref(props.getProperty(key)?.toBooleanStrictOrNull() ?: def) { put(key, it.toString()) }

    private fun stringPref(key: String, def: String) =
        Pref(props.getProperty(key) ?: def) { put(key, it) }

    private fun longPref(key: String, def: Long) =
        Pref(props.getProperty(key)?.toLongOrNull() ?: def) { put(key, it.toString()) }

    private fun setPref(key: String, def: Set<String>) =
        Pref(props.getProperty(key)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: def) {
            put(key, it.joinToString(","))
        }

    /** Maximum video height; 0 = adapt to the screen. */
    val maxQuality = intPref("max_quality", 0)

    /** auto | avc | vp9 | av1 */
    val codec = stringPref("codec", "auto")

    val autoplay = boolPref("autoplay", true)

    /** off | fr | original */
    val subtitles = stringPref("subtitles", "off")

    val sponsorBlock = boolPref("sponsorblock", true)
    val sponsorCategories = setPref("sponsorblock_categories", DEFAULT_SPONSOR_CATEGORIES)

    val contentCountry = stringPref("content_country", "FR")

    /** red | gold | blue | violet */
    val accent = stringPref("accent", "red")

    val lastUpdateCheck = longPref("last_update_check", 0L)

    /** 0..150 (VLC volume). */
    val volume = intPref("volume", 100)

    val startFullscreen = boolPref("start_fullscreen", false)

    companion object {
        val DEFAULT_SPONSOR_CATEGORIES = setOf("sponsor", "selfpromo", "interaction")
    }
}
