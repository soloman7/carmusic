package com.carmusic.playback

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.carmusic.CarMusicApp
import com.carmusic.data.FavoriteEntity
import com.carmusic.data.HistoryEntity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * 车载音乐播放前台服务
 * MediaLibraryService 同时支持：
 *  - 熄屏/通知栏/锁屏控制卡片（MediaSessionService 能力）
 *  - Android Auto browsable 树（收藏 / 历史 / 搜索占位）
 *  - 方向盘/蓝牙媒体键接管（DOWN 即切歌）
 */
class PlaybackService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "carmusic_root"
        const val CAT_FAVORITE = "cat_favorite"
        const val CAT_HISTORY = "cat_history"
        const val CAT_SEARCH_HINT = "cat_search_hint"
        /** 同键去抖窗口：DiLink 双发 DOWN 间隔 <100ms；人类连按 ≥250ms，不能误吞 */
        private const val DEBOUNCE_MS = 250L
    }

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastDownKeyCode = 0
    private var lastDownAt = 0L

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            // 统一走 AppContainer 的 OkHttp（自定义 UA：咪咕 freetyst CDN 对
            // ExoPlayer 默认 UA 不友好；也便于后续按平台加 Referer）
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                            CarMusicApp.instance.container.okHttpClient
                        ).setUserAgent(com.carmusic.BuildConfig.UA)
                    )
            )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // 推送 audioSessionId 给 EqManager（MediaController 无此 getter，同进程直读 ExoPlayer）
        AudioSessionHub.audioSessionId.value = player.audioSessionId
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioSessionHub.audioSessionId.value = audioSessionId
            }
        })

        val sessionIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .apply { sessionIntent?.let { setSessionActivity(it) } }
            .build()
    }

    inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browsable(ROOT_ID, "CarMusic"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            val db = CarMusicApp.instance.container.database
            val items: List<MediaItem> = when (parentId) {
                ROOT_ID -> listOf(
                    browsable(CAT_FAVORITE, "收藏"),
                    browsable(CAT_HISTORY, "最近播放"),
                    browsable(CAT_SEARCH_HINT, "搜索（在 App 中）")
                )
                CAT_FAVORITE -> db.favoriteDao().getAll().take(50).map { it.toPlayable() }
                CAT_HISTORY -> db.historyDao().getRecentFlow(50).first().map { it.toPlayable() }
                else -> emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            val db = CarMusicApp.instance.container.database
            val fav = db.favoriteDao().getAll().find { it.trackId == mediaId }
            val his = db.historyDao().getRecentFlow(200).first().find { it.trackId == mediaId }
            val item = fav?.toPlayable() ?: his?.toPlayable()
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /**
         * 方向盘/蓝牙媒体键：DOWN 即切歌。
         * 实测 DiLink 短按只发 DOWN 不发 UP（v2.4 把切歌挂在 UP 上导致完全失效），
         * 快进快退已按用户要求移除。DOWN/UP 全部消费，不走 Media3 默认路径。
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            @Suppress("DEPRECATION")
            val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            val keyCode = ev.keyCode
            if (keyCode != KeyEvent.KEYCODE_MEDIA_NEXT && keyCode != KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                return false
            }

            if (ev.action == KeyEvent.ACTION_DOWN) {
                if (ev.repeatCount > 0) return true   // 长按系统重复事件，忽略（防连跳）
                val now = ev.eventTime
                // DiLink 双发 DOWN 去抖；被吞时不更新 lastDownAt，防链式误吞合法快按
                if (keyCode == lastDownKeyCode && now - lastDownAt < DEBOUNCE_MS) {
                    return true
                }
                lastDownKeyCode = keyCode
                lastDownAt = now
                val pm = CarMusicApp.instance.container.playerManager
                // controller 未连接（媒体键冷启动）时直连 session.player
                val fallback = if (pm.isControllerReady) null else session.player
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) pm.nextOn(fallback)
                else pm.previousOn(fallback)
            }
            return true
        }
    }

    private fun browsable(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun FavoriteEntity.toPlayable(): MediaItem = MediaItem.Builder()
        .setMediaId(trackId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUrl?.let { android.net.Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private fun HistoryEntity.toPlayable(): MediaItem = MediaItem.Builder()
        .setMediaId(trackId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUrl?.let { android.net.Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // 归零 session id，触发 EqManager detach（效果器跟随已释放的 ExoPlayer 会话）
        AudioSessionHub.audioSessionId.value = 0
        serviceScope.cancel()
        super.onDestroy()
    }
}
