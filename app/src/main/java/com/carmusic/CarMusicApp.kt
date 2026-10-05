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
            // v3.9:显式限 64MB(默认 250MB)。车机存储有限,58k 电台 favicon 场景下
            // 64MB 足够热区工作集,LRU 淘汰旧封面(死 favicon 不重验,靠淘汰出局)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    companion object {
        lateinit var instance: CarMusicApp
            private set
    }
}
