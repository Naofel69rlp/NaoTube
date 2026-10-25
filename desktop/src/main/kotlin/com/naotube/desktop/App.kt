/*
 * NaoTube — version Windows
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.desktop

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.naotube.tv.core.NaoDownloader
import com.naotube.tv.data.SponsorBlock
import com.naotube.tv.data.YouTubeRepository
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

/** Everything the app shares: network, YouTube engine, settings and the user's library. */
object Nao {
    lateinit var httpClient: OkHttpClient
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var repository: YouTubeRepository
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var sponsorBlock: SponsorBlock
        private set
    lateinit var updater: Updater
        private set

    private var started = false

    @Synchronized
    fun init() {
        if (started) return
        started = true
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        settings = AppSettings()
        NewPipe.init(NaoDownloader(httpClient))
        applyContentCountry(settings.contentCountry.value)
        repository = YouTubeRepository()
        library = LibraryStore()
        sponsorBlock = SponsorBlock(httpClient)
        updater = Updater(httpClient)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = httpClient)) }
                .crossfade(true)
                .build()
        }
    }

    /** French texts from YouTube ("il y a 2 jours"…), country chosen in the settings. */
    fun applyContentCountry(country: String) {
        NewPipe.setPreferredLocalization(Localization("fr", country))
        NewPipe.setPreferredContentCountry(ContentCountry(country))
    }
}
