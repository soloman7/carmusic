package com.carmusic.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ApiCache 语义测试。
 * 回归背景：v3.3 及之前调用方把 runCatching 放进 block，网络失败被缓存成"空结果"
 * 5~30 分钟——失败 ≠ 无结果，缓存层必须拒绝缓存异常路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiCacheTest {

    @Before
    fun setUp() = ApiCache.clear()

    @Test
    fun `failure is not cached - retry recomputes`() = runTest {
        var calls = 0
        repeat(3) {
            runCatching {
                ApiCache.getOrPut("k") { calls++; error("network down") }
            }
        }
        assertEquals("每次失败重试都应重新执行 block（没有命中假缓存）", 3, calls)
        assertTrue("失败后缓存里不能有条目", ApiCache.peekFresh("k") == null)

        // 恢复后正常写入
        val v = ApiCache.getOrPut("k") { calls++; "ok" }
        assertEquals("ok", v)
        assertEquals(4, calls)
        // 命中缓存，不再执行 block
        assertEquals("ok", ApiCache.getOrPut("k") { calls++; "no" })
        assertEquals(4, calls)
    }

    @Test
    fun `concurrent same key executes block once`() = runTest {
        var calls = 0
        val results = (1..20).map {
            async(Dispatchers.IO) {
                ApiCache.getOrPut("ck") {
                    synchronized(this@ApiCacheTest) { calls++ }
                    delay(50)
                    "v"
                }
            }
        }.awaitAll()
        results.forEach { assertEquals("v", it) }
        assertEquals("并发同 key 只算一次", 1, calls)
    }

    @Test
    fun `success value is cached with ttl`() = runTest {
        ApiCache.putDirect("t", 42, ttlMs = 60_000)
        assertEquals(42, ApiCache.peekFresh("t"))
    }

    @Test
    fun `searchKey distinguishes null emptyset and explicit sets`() {
        assertEquals("search:kw:all", SourceManager.searchKey("kw", null))
        assertEquals("search:kw:none", SourceManager.searchKey("kw", emptySet()))
        assertEquals("search:kw:a,b", SourceManager.searchKey("kw", setOf("b", "a")))
        // v3.3 bug：null 与 emptySet() 折叠成同一 key，空集请求污染全平台缓存
        assertFalse(SourceManager.searchKey("kw", null) == SourceManager.searchKey("kw", emptySet()))
    }
}
