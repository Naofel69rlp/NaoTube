/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("naotube_settings", Context.MODE_PRIVATE)

    private fun intPref(key: String, def: Int) =
        Pref(prefs.getInt(key, def)) { prefs.edit().putInt(key, it).apply() }

    private fun boolPref(key: String, def: Boolean) =
        Pref(prefs.getBoolean(key, def)) { prefs.edit().putBoolean(key, it).apply() }

    private fun stringPref(key: String, def: String) =
        Pref(prefs.getString(key, def) ?: def) { prefs.edit().putString(key, it).apply() }

    private fun longPref(key: String, def: Long) =
        Pref(prefs.getLong(key, def)) { prefs.edit().putLong(key, it).apply() }

    private fun setPref(key: String, def: Set<String>) =
        Pref(prefs.getStringSet(key, def)?.toSet() ?: def) {
            prefs.edit().putStringSet(key, it).apply()
        }

    /** Maximum video height; 0 = adapt to what the TV can display. */
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

    companion object {
        val DEFAULT_SPONSOR_CATEGORIES = setOf("sponsor", "selfpromo", "interaction")
    }
}
