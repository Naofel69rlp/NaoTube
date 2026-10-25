/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.player

import androidx.media3.common.Player

/** The player currently on screen, so the activity can pause it when the app is left. */
object ActivePlayer {
    @Volatile
    var current: Player? = null

    fun pause() {
        current?.pause()
    }
}
