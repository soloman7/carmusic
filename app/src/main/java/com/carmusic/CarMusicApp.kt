package com.carmusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.carmusic.crash.CrashHandler
import com.carmusic.di.AppContainer

class CarMusicApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.install(this)
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(container.okHttpClient)
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        lateinit var instance: CarMusicApp
            private set
    }
}
