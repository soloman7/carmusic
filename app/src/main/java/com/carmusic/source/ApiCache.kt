package com.carmusic.source

import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 应用层内存缓存：搜索结果 5 分钟 TTL */
object ApiCache {
    private data class Entry(val data: Any, val expiresAt: Long)

    private val cache = LruCache<String, Entry>(64)

    /** per-key 锁：同一 key 的并发 miss 只发一次请求（原来锁内查、锁外算会重复请求） */
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> getOrPut(key: String, ttlMs: Long = 5 * 60_000, block: suspend () -> T): T {
        val keyMutex = locks.getOrPut(key) { Mutex() }
        val result = keyMutex.withLock {
            cache.get(key)?.takeIf { it.expiresAt > System.currentTimeMillis() }
                ?.let { return@withLock it.data as T }
            val v = block()
            cache.put(key, Entry(v as Any, System.currentTimeMillis() + ttlMs))
            v
        }
        // 防泄漏：无竞争时回收锁对象（key 含搜索词，空间无界）。
        // 取舍：check-then-remove 有竞态，最差情况两协程各持一个 Mutex 并发算一次——
        // 退化回修复前的行为，可接受；比常驻一个无界 Map 划算。
        if (!keyMutex.isLocked) locks.remove(key, keyMutex)
        return result
    }

    fun invalidate(prefix: String) {
        val keys = cache.snapshot().keys.filter { it.startsWith(prefix) }
        keys.forEach {
            cache.remove(it)
            locks.remove(it)
        }
    }

    fun clear() {
        cache.evictAll()
        locks.clear()
    }
}
