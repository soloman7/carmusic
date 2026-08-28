package com.carmusic.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.carmusic.BuildConfig
import com.carmusic.data.SettingsRepository
import com.carmusic.source.providers.getJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 应用自动更新（v3.2 新增）。
 *
 * DiLink 车机没有应用商店，更新走自托管 version.json：
 * { "versionCode": 19, "versionName": "3.2.0", "url": "https://.../carmusic-x.y.z-release.apk", "notes": "更新说明" }
 * 地址在设置页配置（update_url），留空 = 功能整体静默关闭。
 *
 * 流程：checkForUpdate 拉 version.json 比 versionCode → downloadApk 下载到 cacheDir/updates
 * → installApk 经 FileProvider 交系统安装器（未授权"安装未知应用"时先跳系统授权页）。
 */
class UpdateManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val settings: SettingsRepository
) {

    /** 远端版本信息（version.json 解析结果） */
    data class RemoteVersion(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        /** APK 的 SHA-256（version.json 可选字段；缺省则跳过校验） */
        val sha256: String = ""
    )

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data object UpToDate : UpdateState()
        data class Available(val info: RemoteVersion) : UpdateState()
        data class Downloading(val percent: Int) : UpdateState()
        /** 下载完成，待用户点击安装 */
        data class Ready(val info: RemoteVersion, val apk: File) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // APK 放 filesDir（cacheDir 会被系统清掉，"下载完成待安装"可能变废纸）
    private val updateDir: File get() = File(context.filesDir, "updates")

    private var downloadCall: Call? = null

    /** 取消进行中的下载（用户离开设置页等场景） */
    fun cancelDownload() {
        downloadCall?.cancel()
    }

    /**
     * 检查更新。force=false 时仅在开启自动检查且配置了地址时执行（启动静默检查用）；
     * 任何失败只落 Error 状态，不抛异常。
     */
    suspend fun checkForUpdate(force: Boolean = false) {
        val url = settings.updateUrl.first()
        if (url.isEmpty()) {
            Log.d(TAG, "update url not configured, skip")
            return
        }
        if (!force && !settings.autoUpdateCheck.first()) return

        _state.value = UpdateState.Checking
        val json = runCatching { okHttpClient.getJson(url) }.getOrNull()
        if (json == null) {
            _state.value = UpdateState.Error("检查更新失败：无法读取更新信息")
            return
        }
        val info = runCatching {
            RemoteVersion(
                versionCode = json.get("versionCode").asInt,
                versionName = json.get("versionName").asString,
                apkUrl = json.get("url").asString,
                notes = json.get("notes")?.asString ?: "",
                sha256 = json.get("sha256")?.asString?.trim() ?: ""
            )
        }.getOrNull()
        if (info == null || info.apkUrl.isEmpty()) {
            _state.value = UpdateState.Error("检查更新失败：更新信息格式错误")
            return
        }
        _state.value = if (info.versionCode > BuildConfig.VERSION_CODE) {
            UpdateState.Available(info)
        } else {
            UpdateState.UpToDate
        }
    }

    /** 下载 APK 到 filesDir/updates，进度经 state 回报；SHA-256 校验通过转 Ready，失败转 Error */
    suspend fun downloadApk(info: RemoteVersion) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(0)
        runCatching {
            updateDir.mkdirs()
            val target = File(updateDir, "carmusic-v${info.versionName}.apk")
            val request = Request.Builder().url(info.apkUrl).build()
            okHttpClient.newCall(request).also { downloadCall = it }.execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: error("下载失败：空响应")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        // 进度按整百分比回报，避免 StateFlow 被 64KB chunk 刷屏
                        var lastPercent = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _state.value = UpdateState.Downloading(percent)
                                }
                            }
                        }
                    }
                }
            }
            verifyChecksum(target, info)
            target
        }.onSuccess { file ->
            // 只保留最新一个安装包
            updateDir.listFiles()?.forEach { if (it != file && it.name.endsWith(".apk")) it.delete() }
            _state.value = UpdateState.Ready(info, file)
        }.onFailure { e ->
            Log.w(TAG, "download failed: ${e.message}")
            _state.value = UpdateState.Error(e.message ?: "下载失败")
        }.also { downloadCall = null }
    }

    /** version.json 带了 sha256 才校验；不匹配删除文件并报错（半截包/被篡改都不能进安装器） */
    private fun verifyChecksum(apk: File, info: RemoteVersion) {
        if (info.sha256.isEmpty()) return
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            apk.delete()
            error("APK 校验失败（SHA-256 不匹配），已取消安装")
        }
    }

    /**
     * 引导安装：未授权"安装未知来源应用"时跳系统授权页；已授权则经 FileProvider 拉起安装器。
     * 返回 true 表示已发出安装意图。
     */
    fun installApk(activity: Context, apk: File): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(intent) }
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    companion object {
        private const val TAG = "UpdateManager"
    }
}
