package com.carmusic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放流补头：猫耳无声根因是 Referer/UA 未进数据源。
 * 精确 URL 表优先于 host 兜底；调用方已有头不覆盖。
 */
class PlaybackHttpHeadersTest {

    @Before
    fun setUp() {
        PlaybackHttpHeaders.clearForTest()
    }

    @Test
    fun `registered headers are returned for exact url`() {
        val url = "https://cdn.example.com/a.m3u8"
        PlaybackHttpHeaders.register(url, mapOf("Referer" to "https://www.missevan.com/"))
        assertEquals("https://www.missevan.com/", PlaybackHttpHeaders.headersFor(url)["Referer"])
    }

    @Test
    fun `missevan host gets default referer and chrome ua`() {
        val h = PlaybackHttpHeaders.defaultHeadersFor("https://cdn.missevan.com/sound/x.m3u8")
        assertEquals("https://www.missevan.com/", h["Referer"])
        assertTrue(h["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test
    fun `other hosts only get chrome ua`() {
        val h = PlaybackHttpHeaders.defaultHeadersFor("https://m701.music.126.net/x.mp3")
        assertNull(h["Referer"])
        assertTrue(h["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test
    fun `interceptor injects missing referer without overwriting existing`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok"))
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(PlaybackHttpHeaders.interceptor)
                .build()
            val req = okhttp3.Request.Builder()
                .url(server.url("/a.mp3"))
                .header("Referer", "https://keep.me/")
                .build()
            client.newCall(req).execute().close()
            val recorded = server.takeRequest()
            assertEquals("https://keep.me/", recorded.getHeader("Referer"))
            assertTrue(recorded.getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
        } finally {
            server.shutdown()
        }
    }
}
