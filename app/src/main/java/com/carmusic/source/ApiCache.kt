package com.carmusic.source

import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 应用层内存缓存：搜索结果 5 分钟 TTL、歌单 30 分钟 TTL。
 *  铁律：block 抛异常 = 本轮结果不可信，绝不写缓存（失败 ≠ 无结果）。 */
object ApiCache {
    private data class Entry(val data: Any, val expiresAt: Long)

    // 256：fallback 搜索的 "search:标题 歌手:" 键会持续写缓存，64 容量会被挤爆正常键
    private val cache = LruCache<String, Entry>(256)

    /** per-key 锁：同一 key 的并发 miss 只发一次请求（原来锁内查、锁外算会重复请求） */
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> getOrPut(key: String, ttlMs: Long = 5 * 60_000, block: suspend () -> T): T {
        val keyMutex = locks.getOrPut(key) { Mutex() }
        val result = keyMutex.withLock {
            cache.get(key)?.takeIf { it.expiresAt > System.currentTimeMillis() }
                ?.let { return@withLock it.data as T }
            // block 抛异常时直接上抛、跳过 cache.put：调用方看到"本轮失败可重试"，
            // 而不是 5~30 分钟内一直命中假"空结果"缓存
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

    /** 未过期命中返回原始值（调用方自行转型），否则 null。流式搜索的快速路径用。 */
    fun peekFresh(key: String): Any? =
        cache.get(key)?.takeIf { it.expiresAt > System.currentTimeMillis() }?.data

    /** 直接写入（不经 getOrPut 的 per-key 锁）。流式搜索收齐各源后写合并结果用。 */
    fun putDirect(key: String, value: Any, ttlMs: Long = 5 * 60_000) {
        cache.put(key, Entry(value, System.currentTimeMillis() + ttlMs))
    }

    fun clear() {
        cache.evictAll()
        locks.clear()
    }
}
