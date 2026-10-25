/*
 * NaoTube — client YouTube pour Android TV
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.naotube.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.core.content.getSystemService
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.naotube.tv.core.AppSettings
import com.naotube.tv.core.NaoDownloader
import com.naotube.tv.data.LibraryStore
import com.naotube.tv.data.SponsorBlock
import com.naotube.tv.data.Updater
import com.naotube.tv.data.YouTubeRepository
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

class NaoTubeApp : Application(), SingletonImageLoader.Factory {

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        settings = AppSettings(this)
        NewPipe.init(NaoDownloader(httpClient))
        applyContentCountry(settings.contentCountry.value)
        repository = YouTubeRepository()
        library = LibraryStore(this)
        sponsorBlock = SponsorBlock(httpClient)
        updater = Updater(this, httpClient)
    }

    /** French UI language for YouTube texts ("il y a 2 jours"…), country chosen in settings. */
    fun applyContentCountry(country: String) {
        NewPipe.setPreferredLocalization(Localization("fr", country))
        NewPipe.setPreferredContentCountry(ContentCountry(country))
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .allowRgb565(getSystemService<ActivityManager>()?.isLowRamDevice == true)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = httpClient))
        }
        .build()

    companion object {
        lateinit var instance: NaoTubeApp
            private set
    }
}
