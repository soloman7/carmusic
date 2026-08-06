package com.carmusic.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缓存目录统计 / 清理。
 *
 * 从 SettingsViewModel 下沉：遍历目录树和递归删除都是重 IO，
 * 原先在 viewModelScope 主线程执行会卡 UI，这里统一切到 Dispatchers.IO。
 * 通过构造参数注入 applicationContext，不再反向抓 CarMusicApp.instance。
 */
class CacheRepository(context: Context) {

    private val appContext = context.applicationContext

    suspend fun calcCacheSizeMB(): Long = withContext(Dispatchers.IO) {
        val dirs = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir)
        val bytes = dirs.sumOf { dir ->
            dir.walkBottomUp().filter { it.isFile }.map { it.length() }.sum()
        }
        bytes / 1024 / 1024
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        appContext.cacheDir?.deleteRecursively()
        appContext.cacheDir?.mkdirs()
        appContext.externalCacheDir?.deleteRecursively()
        appContext.externalCacheDir?.mkdirs()
    }
}
