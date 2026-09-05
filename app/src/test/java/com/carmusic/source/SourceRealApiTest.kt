package com.carmusic.source

import com.carmusic.source.providers.MiguSource
import com.carmusic.source.providers.QQSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 新链路真实网络实测（默认跳过，`gradlew testDebugUnitTest -PintegrationTests` 时执行）。
 * 覆盖 v2.4 复刻 Listen 1 的每条链路：广场列表 → 歌单曲目 → 播放地址。
 * 网易链路用 scripts/test_netease_weapi.py 验证（crypto 依赖 android.util.Base64，JVM 跑不了）。
 */
class SourceRealApiTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun requireIntegration() =
        assumeTrue("需要 -PintegrationTests 才执行真实网络测试", System.getProperty("carmusic.integrationTests") == "true")

    @Test
    fun qqSquareAndDissTracks() = runBlocking {
        requireIntegration()
        val qq = QQSource(client)
        val square = qq.getPlaylistSquare(0)
        println("=== QQ 广场 ${square.size} 个歌单 ===")
        square.take(3).forEach { println("  ${it.id} | ${it.name}") }
        assertTrue("QQ 歌单广场为空", square.isNotEmpty())

        val tracks = qq.getPlaylistTracks(square.first())
        println("=== 首个 diss 歌单 ${tracks.size} 首 ===")
        tracks.take(3).forEach { println("  ${it.title} | ${it.artist}") }
        assertTrue("QQ diss 歌单曲目为空", tracks.isNotEmpty())
    }

    @Test
    fun miguFullChain() = runBlocking {
        requireIntegration()
        val migu = MiguSource(client)
        val square = migu.getPlaylistSquare(0)
        println("=== 咪咕广场 ${square.size} 个歌单 ===")
        square.take(3).forEach { println("  ${it.id} | ${it.name}") }
        assertTrue("咪咕歌单广场为空", square.isNotEmpty())

        val tracks = migu.getPlaylistTracks(square.first())
        println("=== 首个歌单 ${tracks.size} 首 ===")
        tracks.take(3).forEach { println("  ${it.title} | ${it.artist} | songId=${it.extra["songId"]}") }
        assertTrue("咪咕歌单曲目为空", tracks.isNotEmpty())

        // 全链路：VIP 歌匿名拿不到地址属预期（cannotCode 440013），
        // 抽前 10 首统计，至少一首可播即链路有效（App 内 VIP 歌走跨平台 fallback）
        val candidates = tracks.filter { !it.extra["contentId"].isNullOrBlank() }.take(10)
        var playable = 0
        var firstUrl: String? = null
        for (t in candidates) {
            val src = runCatching { migu.getMediaSource(t) }.getOrNull()
            if (src != null) {
                playable++
                if (firstUrl == null) firstUrl = src.url
            }
        }
        println("=== 咪咕可播率: $playable/${candidates.size} ===")
        println("=== 播放地址: ${firstUrl?.take(80)} ===")
        assertTrue("咪咕前 10 首全不可播，播放链路失效", playable > 0)
    }
}
