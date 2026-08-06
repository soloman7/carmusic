package com.carmusic.di

import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.carmusic.crash.CrashHandler
import com.carmusic.data.AppDatabase
import com.carmusic.data.CacheRepository
import com.carmusic.data.SettingsRepository
import com.carmusic.drive.DrivingDetector
import com.carmusic.lyric.LyricRepository
import com.carmusic.playback.PlayerManager
import com.carmusic.source.SourceManager
import com.carmusic.ui.drive.DriveViewModel
import com.carmusic.ui.library.FavoriteViewModel
import com.carmusic.ui.library.HistoryViewModel
import com.carmusic.ui.player.PlayerViewModel
import com.carmusic.ui.playlist.PlaylistViewModel
import com.carmusic.ui.search.SearchViewModel
import com.carmusic.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .cache(Cache(File(appContext.cacheDir, "http"), 50L * 1024 * 1024))
        .retryOnConnectionFailure(true)
        .build()

    val database: AppDatabase = AppDatabase.build(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val cacheRepository: CacheRepository = CacheRepository(appContext)
    val sourceManager: SourceManager = SourceManager(okHttpClient, settingsRepository)
    val lyricRepository: LyricRepository = LyricRepository(database.lyricDao(), sourceManager)
    val playerManager: PlayerManager = PlayerManager(appContext, sourceManager, database, settingsRepository)
    val eqManager: com.carmusic.playback.EqManager = com.carmusic.playback.EqManager(settingsRepository, playerManager)
    val drivingDetector: DrivingDetector = DrivingDetector(appContext)

    // 容器级协程：跟随设置变化把 SMTP host/port 注入 CrashHandler（崩溃日志导出用）
    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        containerScope.launch {
            combine(settingsRepository.smtpHost, settingsRepository.smtpPort) { h, p -> h to p }
                .collect { (host, port) -> CrashHandler.configureSmtp(host, port) }
        }
    }

    val playerVMFactory = viewModelFactory {
        initializer { PlayerViewModel(playerManager, lyricRepository, database.favoriteDao()) }
    }
    val searchVMFactory = viewModelFactory {
        initializer { SearchViewModel(sourceManager, settingsRepository, playerManager, drivingDetector) }
    }
    val favoriteVMFactory = viewModelFactory {
        initializer { FavoriteViewModel(database.favoriteDao(), playerManager) }
    }
    val historyVMFactory = viewModelFactory {
        initializer { HistoryViewModel(database.historyDao(), playerManager) }
    }
    val driveVMFactory = viewModelFactory {
        initializer { DriveViewModel(playerManager, database.favoriteDao()) }
    }
    val playlistVMFactory = viewModelFactory {
        initializer { PlaylistViewModel(sourceManager, playerManager) }
    }
    val settingsVMFactory = viewModelFactory {
        initializer { SettingsViewModel(settingsRepository, cacheRepository, appContext) }
    }
    val eqVMFactory = viewModelFactory {
        initializer { com.carmusic.ui.eq.EqViewModel(eqManager) }
    }
}
