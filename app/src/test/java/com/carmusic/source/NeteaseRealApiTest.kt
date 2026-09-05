package com.carmusic.source

import com.carmusic.source.providers.NeteaseSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真实网络接口实测（默认跳过，`gradlew testDebugUnitTest -PintegrationTests` 时执行）。
 * 用于验证网易云 weapi 新接口（toplist / highquality）匿名可用性。
 */
class NeteaseRealApiTest {

    private fun requireIntegration() =
        assumeTrue("需要 -PintegrationTests 才执行真实网络测试", System.getProperty("carmusic.integrationTests") == "true")

    @Test
    fun recommendedPlaylists_realNetwork() = runBlocking {
        requireIntegration()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val source = NeteaseSource(client)
        val playlists = source.getRecommendedPlaylists()
        println("=== 共 ${playlists.size} 个歌单 ===")
        playlists.forEach { println("${it.id} | ${it.name} | ${it.trackCount} | ${it.description}") }
        assertTrue("推荐歌单为空，接口可能已失效", playlists.isNotEmpty())
    }

    @Test
    fun playlistTracks_realNetwork() = runBlocking {
        requireIntegration()
        // weapi 链路依赖 android.util.Base64：JVM(returnDefaultValues) 下返回 null 必 NPE，
        // 该链路由 scripts/test_netease_weapi.py 验证；在真机/插桩环境本测试自动恢复执行
        org.junit.Assume.assumeTrue(
            android.util.Base64.encodeToString(byteArrayOf(1), android.util.Base64.NO_WRAP) != null
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val source = NeteaseSource(client)
        val playlist = com.carmusic.source.model.Playlist(
            platform = "netease", id = "3778678", name = "热歌榜"
        )
        val tracks = source.getPlaylistTracks(playlist)
        val grey = tracks.count { it.extra["grey"] == "1" }
        println("=== 共 ${tracks.size} 首，其中灰歌 $grey 首 ===")
        tracks.take(5).forEach { println("${it.id} | ${it.title} | ${it.artist} | grey=${it.extra["grey"]}") }
        assertTrue("歌单曲目为空", tracks.isNotEmpty())
    }
}
