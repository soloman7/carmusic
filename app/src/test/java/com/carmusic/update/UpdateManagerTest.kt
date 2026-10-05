package com.carmusic.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * 更新链路测试(v3.9,此前全系统唯一"软件来源"零测试):
 * 下载成功/SHA 不匹配删包/断点续传(Range 头 + 206 拼接)/https 强制/半截包保留策略。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: UpdateManager
    private lateinit var settings: SettingsRepository

    /** 4KB 伪 APK 与其 SHA-256 */
    private val payload = ByteArray(4096) { (it % 251).toByte() }
    private val payloadSha: String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun remoteInfo(sha: String = payloadSha) = UpdateManager.RemoteVersion(
        versionCode = 99, versionName = "9.9.9", apkUrl = server.url("/app.apk").toString(),
        notes = "", sha256 = sha
    )

    private fun apkFile(): File =
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "updates/carmusic-v9.9.9.apk")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsRepository(context)
        manager = UpdateManager(context, OkHttpClient.Builder().build(), settings)
        apkFile().delete()
    }

    @After
    fun tearDown() {
        apkFile().delete()
        server.shutdown()
    }

    @Test
    fun `download success verifies sha and enters ready`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        manager.downloadApk(remoteInfo())
        val state = manager.state.value
        assertTrue("应进入 Ready: $state", state is UpdateManager.UpdateState.Ready)
        assertTrue(apkFile().exists())
        assertEquals(payload.size.toLong(), apkFile().length())
    }

    @Test
    fun `sha mismatch deletes file and reports error`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        manager.downloadApk(remoteInfo(sha = "deadbeef".repeat(8)))
        val state = manager.state.value
        assertTrue("应进入 Error: $state", state is UpdateManager.UpdateState.Error)
        assertFalse("校验失败的坏包必须删除", apkFile().exists())
    }

    @Test
    fun `resume sends range header and assembles full file from 206`() = runBlocking {
        // 断点位置由 MockWebServer 决定(任意字节处断),第二跳按 Range 头动态回后缀
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range == null) {
                    MockResponse().setBody(okio.Buffer().write(payload))
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    MockResponse().setResponseCode(206)
                        .setBody(okio.Buffer().write(payload.copyOfRange(start, payload.size)))
                }
            }
        }
        manager.downloadApk(remoteInfo())

        val state = manager.state.value
        assertTrue("续传后应 Ready: $state", state is UpdateManager.UpdateState.Ready)
        assertTrue("拼接后文件必须完整", apkFile().length() == payload.size.toLong())
        assertNull("首次请求不得带 Range", server.takeRequest().getHeader("Range"))
        val range = server.takeRequest().getHeader("Range")
        assertTrue("续传请求必须带 Range 头: $range", range != null && range.startsWith("bytes="))
    }

    @Test
    fun `partial file is kept on failure for later resume`() = runBlocking {
        val half = 2048
        server.enqueue(
            MockResponse().setBody(okio.Buffer().write(payload.copyOfRange(0, half)))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        // 重试 3 次全部断连(每次都只给一半)
        repeat(2) {
            server.enqueue(
                MockResponse().setBody(okio.Buffer().write(payload.copyOfRange(0, half)))
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
        }
        manager.downloadApk(remoteInfo())
        assertTrue(manager.state.value is UpdateManager.UpdateState.Error)
        assertTrue("失败后半截包保留供续传(不再是旧版的直接删除)", apkFile().exists())
    }

    @Test
    fun `non https urls are rejected without network`() = runBlocking {
        settings.setUpdateUrl("http://example.com/version.json")
        manager.checkForUpdate(force = true)
        assertEquals("更新地址必须为 https", (manager.state.value as UpdateManager.UpdateState.Error).message)
    }
}
