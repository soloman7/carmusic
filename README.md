# 车载音乐 CarMusic

- **车载音乐 CarMusic**：为 BYD 元 PLUS 车机（DiLink / Android 10+）设计的聚合音乐播放 APK。当前版本 v3.4.0。

## 特性

- **7 平台聚合搜索**：网易云 / QQ 音乐 / 酷狗 / 酷我 / 咪咕 / Jamendo 免费电台 / 猫耳FM
- **流式聚合搜索**：单源 8s 超时，快源先上屏、慢源后补，搜索延迟不被最慢平台绑架
- **失败不进缓存**（v3.4）：网络故障与"真的没有结果"严格区分，断网一次不会让搜索/歌单在缓存里空转 5~30 分钟
- **播放器进程级复活**（v3.4）：杀 Activity 留进程（DiLink 常态）后重进 App，MediaController 自动重连，播放功能不再失联
- **歌单推荐 + 歌单广场**：推荐页聚合歌单（网易/QQ/咪咕/酷狗/酷我 12 榜单/Jamendo/猫耳主题集），广场支持网易/QQ/咪咕/酷狗分页加载，一键整单播放
- **无效内容预过滤**：咪咕会员歌（showTags=vip）、猫耳付费剧集（pay_type=2）、网易灰歌、QQ VIP 在歌单加载时即剔除，只显示能播的
- **歌词同步滚动**：LrcView 实现，支持点行 seek，歌词本地缓存
- **深色车载 UI**：全局深色主题，夜间驾驶友好，大按钮大字体
- **横屏双栏布局**：封面+控制在左，歌词在右
- **驾驶模式**：GPS 测速自动进入（进入：连续 3 个样本 >5km/h；退出：连续 10 个样本 <3km/h，堵车蠕行不误退；滞回带内维持原状态），极简大按钮界面，行驶中盲按
- **播放会话恢复**：队列 + 曲目 + 进度落盘（Room），DiLink 杀进程后点播放/方向盘按键即续播"停车前听到哪"
- **均衡器 EQ**：基于系统 Equalizer 的频段调节与预设
- **方向盘 / 蓝牙按键**：MediaLibrarySession 自动接管
- **Android Auto 浏览树**：MediaLibrarySession 暴露媒体库，车机/Auto 侧可浏览
- **后台播放**：前台服务，熄屏继续播
- **音频焦点**：导航打断自动 ducking
- **URL 失效自动续签**：20 分钟-2 小时时效，过期自动重拉；备用解析源兜底
- **收藏 + 历史**：Room 持久化
- **崩溃自恢复**：CrashHandler 捕获未处理异常并记录（v3.4 修复保留策略：始终保留最新 20 条）
- **自动更新**：设置页配置 version.json 地址（v3.4 起强制 https），启动时静默检查新版本，一键下载安装（支持 SHA-256 校验、取消下载；车机无应用商店场景）。签名一致性由系统安装器保证，sha256 防半截包
- **每周自动清理**：启动时检测距上次清理超 7 天，自动剔除收藏/历史中的死链歌曲（连续两个清理周期探测失败才删，删除前先过网络连通性哨兵；探测超时不计入死链证据，弱网误判不删数据；黑名单歌单每轮重新探测，恢复即自动移出；清理全程避让播放）、加载失败的推荐歌单（进黑名单自动隐藏）和 30 天前的歌词缓存；可在设置页手动触发

## 技术栈

- Kotlin + Jetpack Compose + Material 3 + Navigation
- Media3 ExoPlayer + MediaLibrarySessionService
- Room + OkHttp + Coil + DataStore
- LrcView (wangchenyan/lrcview)
- 加密：BouncyCastle（网易 weapi AES+RSA 移植自 Listen 1）

## 项目结构

```
app/src/main/java/com/carmusic/
├── CarMusicApp.kt          # Application
├── crash/
│   └── CrashHandler.kt     # 未捕获异常记录 + 落盘
├── di/
│   └── AppContainer.kt     # 手工依赖注入容器
├── drive/
│   └── DrivingDetector.kt  # GPS 测速驾驶检测
├── playback/
│   ├── PlaybackService.kt  # MediaLibrarySessionService 前台服务（Auto 浏览树）
│   ├── PlayerManager.kt    # 播放器状态管理
│   ├── EqManager.kt        # 均衡器
│   ├── AudioSessionHub.kt  # 音频会话 ID 分发
│   └── QueueNavigator.kt   # 播放队列导航
├── source/
│   ├── MusicSource.kt      # 音源接口
│   ├── SourceManager.kt    # 7 平台聚合 + 备用解析 fallback
│   ├── ApiCache.kt         # 接口缓存
│   ├── crypto/NeteaseCrypto.kt
│   └── providers/          # 各平台 provider
│       ├── NeteaseSource.kt
│       ├── QQSource.kt
│       ├── KugouSource.kt
│       ├── KuwoSource.kt
│       ├── MiguSource.kt
│       ├── JamendoSource.kt
│       ├── MaoerSource.kt
│       └── GdStudioSource.kt  # 备用解析源
├── lyric/LyricRepository.kt
├── update/
│   └── UpdateManager.kt    # 自动更新：version.json 检查 → APK 下载 → 引导安装
├── maintenance/
│   └── ContentCleaner.kt   # 每周清理：死链歌曲 / 无效歌单黑名单 / 过期歌词
├── data/                   # Room 数据库 + DataStore 设置
│   ├── Entities.kt
│   ├── Daos.kt
│   ├── AppDatabase.kt
│   └── SettingsRepository.kt
└── ui/
    ├── MainActivity.kt
    ├── theme/              # 深色车载主题 + 平台色/按压组件
    ├── player/PlayerScreen.kt
    ├── search/SearchScreen.kt
    ├── playlist/           # 推荐歌单面板（网格层 ⇄ 曲目层）
    ├── library/            # 收藏 / 播放历史
    ├── settings/           # 设置页
    ├── eq/                 # 均衡器对话框
    └── drive/DriveModeScreen.kt
```

## 歌单覆盖（2026-08-24 线上实测，v3.2 扩充后）

| 平台 | 推荐歌单 | 歌单广场 | 备注 |
|---|---|---|---|
| 网易云 | 榜单+精品+个性化 | ✅ 分页 | weapi AES+RSA |
| QQ 音乐 | 18 硬编码榜单 | ✅ 分页 | vkey 预检剔除 VIP |
| 咪咕 | = 广场首页 | ✅ 分页 | showTags=vip 会员歌预过滤 |
| 酷狗 | 9 榜单 | ✅ 分页 | plist/index + special/song |
| 酷我 | 17 榜单 | ❌ 无匿名广场 API | kbangserver ksong.s |
| Jamendo | 周/总榜+31 主题 | = 主题精选 | 服务端偶发空返回，已加重试 |
| 猫耳FM | 12 个关键词主题集 | ❌ 官方歌单 API 已全 404 | 搜索快照充当，pay_type=2 付费剧集过滤；getsound 已上 WAF，必须带站内 Referer（v3.4） |

## 验证与门禁

- 单元测试：`gradlew.bat testDebugUnitTest`（62 条，默认跳过真实网络测试）
- 真实网络测试：`gradlew.bat testDebugUnitTest -PintegrationTests`
- 音源有效性回归：`python scripts/test_playlist_sources.py`（任何失效项 exit 1，发版红线）
- 发版流程：见 `docs/RELEASE_CHECKLIST.md`

有效性回归脚本：`python scripts/test_playlist_sources.py`

## 构建

```bash
gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 安装到 BYD 车机

方法 1（U盘）：
1. 把 `app-debug.apk` 拷到 U盘
2. U盘插入车机 USB 口
3. 车机文件管理器找到 APK，点击安装（首次需开启"未知来源"）

方法 2（车机浏览器）：
1. 把 APK 上传到网盘
2. 车机浏览器下载并安装

方法 3（ADB，开发者）：
```bash
adb connect <车机IP>:5555
adb install app-debug.apk
```

## DiLink 后台白名单

DiLink 系统对后台进程管理严格，**必须**将 CarMusic 加入白名单：

1. 车机设置 → 应用管理 → CarMusic → 电池 → **允许后台活动**
2. 车机设置 → 应用管理 → CarMusic → **自启动** → 开启
3. 部分 DiLink 版本：手机管家 → 加速白名单 → 添加 CarMusic

## 功能验证清单

- [ ] 搜索"周杰伦"返回多平台结果
- [ ] 点击歌曲播放，进度条正常
- [ ] 歌词同步滚动，点行 seek
- [ ] 熄屏音乐继续，通知栏有控制卡片
- [ ] 方向盘按键能切歌
- [ ] 打开导航音乐音量自动降低
- [ ] 收藏一首歌，重启 APP 仍在
- [ ] 横屏封面/歌词分栏
- [ ] 起步后自动进入驾驶模式，大按钮可用
- [ ] 均衡器调节实时生效

## 风险与限制

- **音源可用性**：网易云/QQ 加密算法可能更新；URL 时效短；猫耳 getsound 已上阿里云 WAF（Referer 可过，策略可能再变）
- **DiLink 兼容性**：不同 BYD 车型 DiLink 版本不同，可能存在差异
- **网络要求**：多平台并发搜索需要稳定网络
- **合规**：仅供个人学习使用，请支持正版音乐
