/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.naotube.tv.player.ActivePlayer
import com.naotube.tv.ui.NaoTubeRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NaoTubeRoot() }
    }

    override fun onStop() {
        // Leaving the app (Home button, screen off…): never keep playing in the background.
        ActivePlayer.pause()
        super.onStop()
    }
}
