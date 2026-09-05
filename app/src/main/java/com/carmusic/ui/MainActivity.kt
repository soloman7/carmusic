package com.carmusic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.carmusic.CarMusicApp
import com.carmusic.ui.drive.DriveModeScreen
import com.carmusic.ui.library.FavoriteScreen
import com.carmusic.ui.library.HistoryScreen
import com.carmusic.ui.player.PlayerScreen
import com.carmusic.ui.search.SearchScreen
import com.carmusic.ui.settings.SettingsScreen
import com.carmusic.ui.theme.CarMusicTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {

    private val container get() = CarMusicApp.instance.container

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 首次安装用户授权后立即启动驾驶检测：
        // onCreate 里的 start() 在权限到位前调用会被忽略，这里不补就永远不启动
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            container.drivingDetector.start()
        }
        // 拒绝也能用基础功能
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // targetSdk 35 起 Android 15 强制 edge-to-edge，提前显式启用统一行为
        enableEdgeToEdge()

        requestNecessaryPermissions()

        // 启动 GPS 驾驶检测（无权限时内部忽略，授权回调里会再调）
        container.drivingDetector.start()

        // 进程存活场景（DiLink 杀 Activity 留进程）重进 App：重连 MediaController
        container.playerManager.ensureConnected()

        setContent {
            CarMusicTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CarMusicNavHost()
                }
            }
        }
    }

    private fun requestNecessaryPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            container.drivingDetector.stop()
            // 只断开 MediaController 连接，绝不 release 进程级单例
            // （scope 一旦取消，重进 App 后所有播放操作静默失效，直到系统杀进程）
            container.playerManager.disconnect()
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun CarMusicNavHost() {
    val navController = rememberNavController()
    val container = CarMusicApp.instance.container
    // 行驶检测跳变防抖：true 需持续 2.5s 才进驾驶模式，false 立即生效，避免误判把用户强拽走
    val isDriving by remember(container) {
        container.drivingDetector.isDriving
            .debounce { driving -> if (driving) 2500L else 0L }
    }.collectAsStateWithLifecycle(initialValue = container.drivingDetector.isDriving.value)

    // 行驶中自动进入驾驶模式
    LaunchedEffect(isDriving) {
        if (isDriving && navController.currentDestination?.route != "drive") {
            navController.navigate("drive")
        }
    }

    NavHost(navController = navController, startDestination = "player") {
        composable("player") { PlayerScreen(navController) }
        composable("search") { SearchScreen(navController) }
        composable("favorite") { FavoriteScreen(navController) }
        composable("history") { HistoryScreen(navController) }
        composable("drive") { DriveModeScreen(navController) }
        composable("settings") { SettingsScreen(navController) }
    }
}
