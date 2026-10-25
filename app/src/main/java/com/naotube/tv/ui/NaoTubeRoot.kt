/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.naotube.tv.R
import com.naotube.tv.ui.components.NaoText
import com.naotube.tv.ui.theme.NaoColors
import com.naotube.tv.ui.theme.NaoTheme
import com.naotube.tv.ui.theme.NaoType

/** Temporary root used to validate the build pipeline; replaced by the full navigation. */
@Composable
fun NaoTubeRoot() {
    NaoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(NaoColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_full),
                    contentDescription = "NaoTube",
                    modifier = Modifier.width(220.dp),
                )
                NaoText("Bientôt prêt…", NaoType.Title)
            }
        }
    }
}
