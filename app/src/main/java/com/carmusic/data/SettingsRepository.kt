package com.carmusic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore("carmusic_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val ALL_PLATFORMS = setOf("migu", "kuwo", "netease", "kugou", "qq", "jamendo", "maoer")
        private val KEY_QUALITY = stringPreferencesKey("quality")
        private val KEY_PLATFORMS = stringSetPreferencesKey("platforms")
        private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play_next")
        private val KEY_PLAY_MODE = stringPreferencesKey("play_mode")
        private val KEY_SMTP_HOST = stringPreferencesKey("smtp_host")
        private val KEY_SMTP_PORT = intPreferencesKey("smtp_port")
        private val KEY_SMTP_USER = stringPreferencesKey("smtp_user")
        private val KEY_SMTP_PASS = stringPreferencesKey("smtp_pass")
        private val KEY_EMAIL_TO = stringPreferencesKey("email_to")
        private val KEY_JAMENDO_CLIENT_ID = stringPreferencesKey("jamendo_client_id")
        private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_EQ_PRESET = stringPreferencesKey("eq_preset")
        private val KEY_EQ_BANDS = stringPreferencesKey("eq_bands")
        private val KEY_EQ_BASS = stringPreferencesKey("eq_bass")
        private val KEY_EQ_VIRTUALIZER = stringPreferencesKey("eq_virtualizer")
    }

    // 注：theme_mode（日间/自动主题）相关 key 与 ThemeMode 已整体移除——
    // 全项目统一深色主题，不再提供主题切换入口。

    // ---- 音质 ----
    val preferredQuality: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_QUALITY] ?: "320k" }

    suspend fun setPreferredQuality(q: String) = context.settingsDataStore.edit { it[KEY_QUALITY] = q }

    // ---- 启用平台 ----
    val enabledPlatforms: Flow<Set<String>> = context.settingsDataStore.data
        .map { it[KEY_PLATFORMS] ?: ALL_PLATFORMS }

    suspend fun setPlatforms(platforms: Set<String>) =
        context.settingsDataStore.edit { it[KEY_PLATFORMS] = platforms }

    suspend fun togglePlatform(p: String) = context.settingsDataStore.edit { pref ->
        val cur = pref[KEY_PLATFORMS] ?: ALL_PLATFORMS
        pref[KEY_PLATFORMS] = if (p in cur) cur - p else cur + p
    }

    // ---- 自动播放下一首 ----
    val autoPlayNext: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_AUTO_PLAY] ?: true }

    suspend fun setAutoPlayNext(b: Boolean) = context.settingsDataStore.edit { it[KEY_AUTO_PLAY] = b }

    // ---- 播放模式（存 PlayMode.name 字符串，由 PlayerManager 解析容错）----
    val playMode: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_PLAY_MODE] ?: "REPEAT_ALL" }

    suspend fun setPlayMode(mode: String) = context.settingsDataStore.edit { it[KEY_PLAY_MODE] = mode }

    // ---- SMTP / 崩溃日志邮箱 ----
    val smtpHost: Flow<String> = context.settingsDataStore.data.map { it[KEY_SMTP_HOST] ?: "smtp.163.com" }
    val smtpPort: Flow<Int> = context.settingsDataStore.data.map { it[KEY_SMTP_PORT] ?: 465 }
    val smtpUser: Flow<String> = context.settingsDataStore.data.map { it[KEY_SMTP_USER] ?: "" }

    /**
     * 授权码在 DataStore 中存密文（见 SmtpCrypto）。
     * 解密失败说明是历史遗留的明文数据，按原文返回兼容；下次保存时会升级为密文。
     */
    val smtpPass: Flow<String> = context.settingsDataStore.data.map { pref ->
        val stored = pref[KEY_SMTP_PASS] ?: ""
        if (stored.isEmpty()) "" else runCatching { SmtpCrypto.decrypt(stored) }.getOrDefault(stored)
    }
    val emailTo: Flow<String> = context.settingsDataStore.data.map { it[KEY_EMAIL_TO] ?: "" }

    suspend fun setSmtpHost(host: String) = context.settingsDataStore.edit {
        it[KEY_SMTP_HOST] = host.trim().ifEmpty { "smtp.163.com" }
    }

    suspend fun setSmtpPort(port: Int) = context.settingsDataStore.edit { it[KEY_SMTP_PORT] = port }

    suspend fun setSmtp(user: String, pass: String, to: String) = context.settingsDataStore.edit {
        it[KEY_SMTP_USER] = user
        // 明文授权码加密后落盘；加密失败（极端情况，如 Keystore 不可用）按原文存储，保证功能可用
        it[KEY_SMTP_PASS] = if (pass.isEmpty()) "" else runCatching { SmtpCrypto.encrypt(pass) }.getOrDefault(pass)
        it[KEY_EMAIL_TO] = to
    }

    // ---- Jamendo client_id（免费电台源；默认已内置用户注册的 id，设置页可改）----
    val jamendoClientId: Flow<String> = context.settingsDataStore.data.map { it[KEY_JAMENDO_CLIENT_ID] ?: "8a49589e" }

    suspend fun setJamendoClientId(id: String) = context.settingsDataStore.edit {
        it[KEY_JAMENDO_CLIENT_ID] = id.trim()
    }

    // ---- 音效（EQ）----
    val eqEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[KEY_EQ_ENABLED] ?: false }
    /** "custom" 或预设序号字符串 */
    val eqPreset: Flow<String> = context.settingsDataStore.data.map { it[KEY_EQ_PRESET] ?: "0" }
    /** 自定义 band 值，逗号分隔 millibel（仅 preset=custom 时使用） */
    val eqBands: Flow<String> = context.settingsDataStore.data.map { it[KEY_EQ_BANDS] ?: "" }
    val eqBass: Flow<Int> = context.settingsDataStore.data.map { (it[KEY_EQ_BASS] ?: "0").toIntOrNull() ?: 0 }
    val eqVirtualizer: Flow<Int> = context.settingsDataStore.data.map { (it[KEY_EQ_VIRTUALIZER] ?: "0").toIntOrNull() ?: 0 }

    suspend fun setEqEnabled(b: Boolean) = context.settingsDataStore.edit { it[KEY_EQ_ENABLED] = b }
    suspend fun setEqPreset(p: String) = context.settingsDataStore.edit { it[KEY_EQ_PRESET] = p }
    suspend fun setEqBands(csv: String) = context.settingsDataStore.edit { it[KEY_EQ_BANDS] = csv }
    suspend fun setEqBass(v: Int) = context.settingsDataStore.edit { it[KEY_EQ_BASS] = v.toString() }
    suspend fun setEqVirtualizer(v: Int) = context.settingsDataStore.edit { it[KEY_EQ_VIRTUALIZER] = v.toString() }
}
