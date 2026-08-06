package com.carmusic.source

import com.carmusic.source.providers.NeteaseSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 真实网络接口实测（手动验证工具，CI 请忽略）。
 * 用于验证网易云 weapi 新接口（toplist / highquality）匿名可用性。
 */
class NeteaseRealApiTest {

    @Test
    @Ignore("手动实测工具：去掉 @Ignore 后运行")
    fun recommendedPlaylists_realNetwork() = runBlocking {
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
    @Ignore("手动实测工具：去掉 @Ignore 后运行")
    fun playlistTracks_realNetwork() = runBlocking {
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
