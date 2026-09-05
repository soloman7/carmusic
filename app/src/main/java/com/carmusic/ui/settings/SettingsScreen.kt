package com.carmusic.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carmusic.BuildConfig
import com.carmusic.CarMusicApp
import com.carmusic.crash.CrashHandler
import com.carmusic.data.CacheRepository
import com.carmusic.data.SettingsRepository
import com.carmusic.maintenance.ContentCleaner
import com.carmusic.ui.theme.CarBackground
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarSurfaceVariant
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.CarTextTertiary
import com.carmusic.ui.theme.PressableIconButton
import com.carmusic.ui.theme.platformDisplayName
import com.carmusic.ui.theme.rememberPressScale
import com.carmusic.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val cacheRepo: CacheRepository,
    private val appContext: Context,
    private val updateManager: com.carmusic.update.UpdateManager,
    private val contentCleaner: com.carmusic.maintenance.ContentCleaner
) : ViewModel() {

    val quality = repo.preferredQuality.stateIn(viewModelScope, SharingStarted.Eagerly, "320k")
    val enabledPlatforms = repo.enabledPlatforms.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.ALL_PLATFORMS)
    val autoPlayNext = repo.autoPlayNext.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val smtpHost = repo.smtpHost.stateIn(viewModelScope, SharingStarted.Eagerly, "smtp.163.com")
    val smtpPort = repo.smtpPort.stateIn(viewModelScope, SharingStarted.Eagerly, 465)
    val smtpUser = repo.smtpUser.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val smtpPass = repo.smtpPass.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val emailTo = repo.emailTo.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val jamendoClientId = repo.jamendoClientId.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // v3.2：自动更新 + 每周清理
    val updateUrl = repo.updateUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val autoUpdateCheck = repo.autoUpdateCheck.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoClean = repo.autoClean.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lastCleanupAt = repo.lastCleanupAt.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val updateState = updateManager.state
    val cleanState = contentCleaner.state

    private val _cacheSizeMB = MutableStateFlow(0L)
    val cacheSizeMB: StateFlow<Long> = _cacheSizeMB.asStateFlow()

    private val _crashCount = MutableStateFlow(0)
    val crashCount: StateFlow<Int> = _crashCount.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    private var lastExportAt = 0L

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            // 目录遍历是重 IO，已在 CacheRepository 内切到 Dispatchers.IO
            _cacheSizeMB.value = cacheRepo.calcCacheSizeMB()
            _crashCount.value = CrashHandler.crashCount(appContext)
        }
    }

    fun setQuality(q: String) = viewModelScope.launch { repo.setPreferredQuality(q) }
    fun togglePlatform(p: String) = viewModelScope.launch { repo.togglePlatform(p) }
    fun setAutoPlay(b: Boolean) = viewModelScope.launch { repo.setAutoPlayNext(b) }
    fun setSmtp(user: String, pass: String, to: String) = viewModelScope.launch { repo.setSmtp(user, pass, to) }
    fun setSmtpHost(host: String) = viewModelScope.launch { repo.setSmtpHost(host) }
    fun setSmtpPort(port: Int) = viewModelScope.launch { repo.setSmtpPort(port) }
    fun setJamendoClientId(id: String) = viewModelScope.launch { repo.setJamendoClientId(id) }

    // v3.2：更新与清理
    fun setUpdateUrl(url: String) = viewModelScope.launch { repo.setUpdateUrl(url) }
    fun setAutoUpdateCheck(b: Boolean) = viewModelScope.launch { repo.setAutoUpdateCheck(b) }
    fun setAutoClean(b: Boolean) = viewModelScope.launch { repo.setAutoClean(b) }
    fun checkUpdate() = viewModelScope.launch { updateManager.checkForUpdate(force = true) }
    fun downloadUpdate(info: com.carmusic.update.UpdateManager.RemoteVersion) =
        viewModelScope.launch { updateManager.downloadApk(info) }
    /** 安装下载好的 APK，返回 false 表示已跳系统授权页（授权后需再点一次安装） */
    fun installUpdate(apk: java.io.File): Boolean = updateManager.installApk(appContext, apk)
    fun cleanNow() = viewModelScope.launch { contentCleaner.runIfDue(force = true) }

    fun clearCache() = viewModelScope.launch {
        // 递归删除同样走 Dispatchers.IO（CacheRepository 内部切换）
        cacheRepo.clearCache()
        refreshStats()
    }

    fun exportCrashLogs() {
        val now = System.currentTimeMillis()
        if (now - lastExportAt < 60_000) {
            _exportStatus.value = "操作太频繁，请 60s 后再试"
            return
        }
        val user = smtpUser.value
        val pass = smtpPass.value
        val to = emailTo.value
        if (user.isBlank() || pass.isBlank() || to.isBlank()) {
            _exportStatus.value = "请先填写 SMTP 用户名 / 授权码 / 收件邮箱"
            return
        }
        lastExportAt = now
        _exportStatus.value = "正在发送…"
        CrashHandler.exportViaEmail(appContext, user, pass, to) { ok, msg ->
            _exportStatus.value = msg
        }
    }
}

@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = viewModel(factory = CarMusicApp.instance.container.settingsVMFactory)
) {
    val quality by vm.quality.collectAsState()
    val enabled by vm.enabledPlatforms.collectAsState()
    val autoPlay by vm.autoPlayNext.collectAsState()
    val smtpHost by vm.smtpHost.collectAsState()
    val smtpPort by vm.smtpPort.collectAsState()
    val smtpUser by vm.smtpUser.collectAsState()
    val smtpPass by vm.smtpPass.collectAsState()
    val emailTo by vm.emailTo.collectAsState()
    val cacheSize by vm.cacheSizeMB.collectAsState()
    val crashCount by vm.crashCount.collectAsState()
    val exportStatus by vm.exportStatus.collectAsState()
    val jamendoClientId by vm.jamendoClientId.collectAsState()
    val updateUrl by vm.updateUrl.collectAsState()
    val autoUpdateCheck by vm.autoUpdateCheck.collectAsState()
    val autoClean by vm.autoClean.collectAsState()
    val lastCleanupAt by vm.lastCleanupAt.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val cleanState by vm.cleanState.collectAsState()

    // DataStore 首帧异步到达，不能用 remember(upstream) 重建输入框，
    // 否则会把用户正在输入的内容清掉；改为首次非空值到达时只填充一次
    val smtpHostInput = rememberSyncedInput(smtpHost)
    val smtpPortInput = rememberSyncedInput(smtpPort.toString())
    val smtpUserInput = rememberSyncedInput(smtpUser)
    val smtpPassInput = rememberSyncedInput(smtpPass)
    val emailToInput = rememberSyncedInput(emailTo)
    val jamendoIdInput = rememberSyncedInput(jamendoClientId)
    val updateUrlInput = rememberSyncedInput(updateUrl)

    var showClearCacheConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CarBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // 顶部
        Row(verticalAlignment = Alignment.CenterVertically) {
            PressableIconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("设置", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 默认音质
        SectionTitle("默认音质")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("128k", "320k", "flac").forEach { q ->
                FilterChip(
                    selected = quality == q,
                    onClick = { vm.setQuality(q) },
                    label = { Text(q, fontSize = MaterialTheme.typography.bodyLarge.fontSize) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CarPrimary,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 启用平台
        SectionTitle("启用平台")
        Column {
            SettingsRepository.ALL_PLATFORMS.forEach { p ->
                val (rowInteraction, rowScale) = rememberPressScale(0.98f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
                        .clickable(
                            interactionSource = rowInteraction,
                            indication = null
                        ) { vm.togglePlatform(p) }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = p in enabled,
                        onCheckedChange = { vm.togglePlatform(p) },
                        colors = CheckboxDefaults.colors(checkedColor = CarPrimary)
                    )
                    Text(platformDisplayName(p), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 自动播放下一首
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自动播放下一首", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = autoPlay,
                onCheckedChange = { vm.setAutoPlay(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = CarPrimary)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 缓存
        SectionTitle("缓存")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$cacheSize MB", color = CarTextSecondary, style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { showClearCacheConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = CarSurfaceVariant)
            ) { Text("清理", color = Color.White) }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Jamendo 免费电台 client_id
        SectionTitle("免费电台（Jamendo）")
        Text(
            "欧美 CC 免费音乐源。到 dev.jamendo.com 免费注册获取 client_id 填入即可启用，留空则不启用。",
            color = CarTextTertiary,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = jamendoIdInput.value,
            onValueChange = { jamendoIdInput.value = it },
            label = { Text("Jamendo client_id", color = CarTextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { vm.setJamendoClientId(jamendoIdInput.value) },
            colors = ButtonDefaults.buttonColors(containerColor = CarSurfaceVariant)
        ) { Text("保存 client_id", color = Color.White) }

        Spacer(modifier = Modifier.height(24.dp))

        // 崩溃日志
        SectionTitle("崩溃日志（$crashCount 条）")
        OutlinedTextField(
            value = smtpHostInput.value,
            onValueChange = { smtpHostInput.value = it },
            label = { Text("SMTP 服务器", color = CarTextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = smtpPortInput.value,
            onValueChange = { smtpPortInput.value = it },
            label = { Text("SMTP 端口（SSL，默认 465）", color = CarTextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = smtpUserInput.value,
            onValueChange = { smtpUserInput.value = it },
            label = { Text("邮箱账号", color = CarTextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = smtpPassInput.value,
            onValueChange = { smtpPassInput.value = it },
            label = { Text("SMTP 授权码", color = CarTextTertiary) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = emailToInput.value,
            onValueChange = { emailToInput.value = it },
            label = { Text("收件邮箱", color = CarTextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    vm.setSmtp(smtpUserInput.value, smtpPassInput.value, emailToInput.value)
                    vm.setSmtpHost(smtpHostInput.value)
                    smtpPortInput.value.toIntOrNull()?.let { vm.setSmtpPort(it) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CarSurfaceVariant)
            ) { Text("保存邮箱配置", color = Color.White) }
            Button(
                onClick = { vm.exportCrashLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = CarPrimary)
            ) { Text("导出崩溃日志", color = Color.Black) }
        }
        exportStatus?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 关于与更新（v3.2）
        SectionTitle("关于与更新")
        Text(
            "当前版本：v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = CarTextSecondary, style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启动时自动检查更新", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = autoUpdateCheck,
                onCheckedChange = { vm.setAutoUpdateCheck(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = CarPrimary)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "车机没有应用商店，更新走自托管 version.json，填入地址即可启用；留空则关闭自动更新。",
            color = CarTextTertiary, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = updateUrlInput.value,
            onValueChange = { updateUrlInput.value = it },
            label = { Text("version.json 地址", color = CarTextTertiary) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = darkFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.setUpdateUrl(updateUrlInput.value) },
                colors = ButtonDefaults.buttonColors(containerColor = CarSurfaceVariant)
            ) { Text("保存地址", color = Color.White) }
            Button(
                onClick = { vm.setUpdateUrl(updateUrlInput.value); vm.checkUpdate() },
                colors = ButtonDefaults.buttonColors(containerColor = CarPrimary)
            ) { Text("检查更新", color = Color.Black) }
        }
        // 更新状态展示
        when (val s = updateState) {
            is UpdateManager.UpdateState.Checking -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在检查更新…", color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateManager.UpdateState.UpToDate -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text("已是最新版本", color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateManager.UpdateState.Available -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "发现新版本 v${s.info.versionName}" + if (s.info.notes.isNotEmpty()) "：${s.info.notes}" else "",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { vm.downloadUpdate(s.info) },
                    colors = ButtonDefaults.buttonColors(containerColor = CarPrimary)
                ) { Text("下载更新", color = Color.Black) }
            }
            is UpdateManager.UpdateState.Downloading -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在下载… ${s.percent}%", color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateManager.UpdateState.Ready -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text("v${s.info.versionName} 已下载完成", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                // LocalContext 必须在 @Composable 上下文取，onClick lambda 里取会编译失败
                val installCtx = androidx.compose.ui.platform.LocalContext.current
                Button(
                    onClick = {
                        if (!vm.installUpdate(s.apk)) {
                            // 返回 false = 已跳系统"安装未知应用"授权页，回来要再点一次
                            android.widget.Toast.makeText(
                                installCtx, "请先允许安装，然后返回再点一次", android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CarPrimary)
                ) { Text("立即安装", color = Color.Black) }
            }
            is UpdateManager.UpdateState.Error -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(s.message, color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {}
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 内容清理（v3.2）
        SectionTitle("内容清理")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("每周自动清理无效内容", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = autoClean,
                onCheckedChange = { vm.setAutoClean(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = CarPrimary)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "清理收藏/历史里的死链歌曲、加载失败的推荐歌单和 30 天前的歌词缓存。",
            color = CarTextTertiary, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (lastCleanupAt > 0)
                    "上次清理：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastCleanupAt))
                else "尚未清理过",
                color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = { vm.cleanNow() },
                colors = ButtonDefaults.buttonColors(containerColor = CarSurfaceVariant)
            ) { Text("立即清理", color = Color.White) }
        }
        when (val s = cleanState) {
            is ContentCleaner.CleanState.Running -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在清理，请稍候…", color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            is ContentCleaner.CleanState.Done -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "清理完成：删除死链歌曲 ${s.removedTracks} 首，无效歌单 ${s.invalidPlaylists} 个",
                    color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium
                )
            }
            is ContentCleaner.CleanState.Error -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(s.message, color = CarTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {}
        }
        Spacer(modifier = Modifier.height(32.dp))
    }

    // 清缓存二次确认
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清理缓存", color = Color.White) },
            text = { Text("将删除全部缓存（封面、网络缓存等，约 $cacheSize MB），收藏和历史不受影响。确定继续？", color = CarTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    vm.clearCache()
                }) { Text("确定清理", color = CarPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消", color = CarTextSecondary) }
            },
            containerColor = CarSurfaceVariant
        )
    }
}

/**
 * 输入框与 DataStore 上游值同步：初始为空，上游首个非空值到达时填充一次，
 * 之后完全以用户输入为准，不再被上游覆盖。
 */
@Composable
private fun rememberSyncedInput(upstream: String): MutableState<String> {
    val state = remember { mutableStateOf("") }
    var filled by remember { mutableStateOf(false) }
    LaunchedEffect(upstream) {
        if (!filled && upstream.isNotEmpty()) {
            state.value = upstream
            filled = true
        }
    }
    return state
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = CarTextSecondary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CarSurfaceVariant,
    unfocusedContainerColor = CarSurfaceVariant,
    focusedBorderColor = CarPrimary,
    unfocusedBorderColor = Color.Transparent,
    cursorColor = CarPrimary,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)
