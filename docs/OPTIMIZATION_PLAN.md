# CarMusic 优化方案(基于 2026-09-05 敌意审查)

## 0. 第一性原理

剥掉所有 feature,这个项目只承诺一件事:

> **每周把能播的音乐可靠地送进车机,且不弄坏上一次还好好的东西。**

由此推出五个不变量,本方案的所有条目都挂在这五条上。任何改动如果不能证明"维护了某个不变量",就不做。

| 不变量 | 含义 | 现状 |
|---|---|---|
| **I1 更新链路必达** | 应用内更新从检查到安装每一步都能走通 | ❌ v3.3 安装步骤必失败(FileProvider) |
| **I2 程序不死、播放不僵** | 任何页面操作/系统行为不会让播放功能失效 | ❌ 单例被 release 后无法复活 |
| **I3 失败不缓存为结果** | 网络失败永远不作为"空结果/无歌词"进入任何缓存 | ❌ 系统性违反 |
| **I4 清理永不删好数据** | 死链删除、黑名单、歌词负缓存都不因网络抖动误伤 | ⚠️ 三处边界漏洞 |
| **I5 可验证** | 每周发版前有自动红线,坏了的东西会被发现 | ❌ 回归脚本 exit 0、真实 API 测试默认跳过 |

**验证基线(2026-09-05 实测,方案起点):**
- `gradlew assembleDebug` ✅
- `gradlew testDebugUnitTest`:51 条,49 过,2 条跳过(真实 API 测试)
- `python scripts/test_playlist_sources.py`:猫耳FM 播放抽查 ❌,酷狗播放抽查 0/3(VIP),**脚本 exit 0**
- git 无敏感文件泄漏(keystore / local.properties / APK 均未入库)

---

## 阶段 0:先把验证本身修好(I5,先于一切代码改动)

> 逻辑:在红线失灵的情况下改代码,等于蒙眼开车。所以第一刀不是 bug,是让"验证"变成可信的。

### 0.1 回归脚本失败必须退出非零
- **改动**:`scripts/test_playlist_sources.py` 汇总后,存在任何 ❌ 项则 `sys.exit(1)`;`➖`(已知的"不实现"项)不算失败。酷狗"播放抽查 0/3 全 VIP"这类已知但可接受的项,单独标记为 `warn`,只告警不拦截。
- **验证**:人为构造一次失败(如临时改坏猫耳 URL)→ 脚本 exit 1;恢复后 exit 0。
- **通过标准**:两个方向都验证过,并把猫耳当前失效记录为已知问题。

### 0.2 真实 API 测试加显式开关
- **改动**:给 `SourceRealApiTest` / `NeteaseRealApiTest` 加 Gradle 项目属性开关(如 `gradlew testDebugUnitTest -PintegrationTests`),默认跳过维持现状,发版前强制跑一次。
- **验证**:不带开关 → 跳过;带开关 → 执行;两个结果都确认。

### 0.3 建立《每周发版门禁》清单
- **改动**:新增 `docs/RELEASE_CHECKLIST.md`,内容 = 构建通过 → 单测通过 → 回归脚本 exit 0 → 真实 API 测试(带开关)通过 → **应用内更新端到端走通(上一版 → 新版,含点"安装")** → SHA256 写入 version.json → 车机冒烟 10 项(README 已有清单)。
- **验证**:拿 v3.3.0 → v3.3.1(阶段 1 的产物)把整份清单走一遍,清单本身作为交付物被验证。

---

## 阶段 1:P0/P1 热修(十个一行级修复)

> 每个 item:改动 → 验证方法 → 通过标准。逐项打勾,不许批量。

### 1.1 FileProvider 双同名 path(I1)—— P0
- **改动**:`app/src/main/res/xml/file_paths.xml` 中 `cache-path` 的 `name` 改为 `updates-cache`。
- **验证**:
  a) 构建通过; b) release 包安装到车机/模拟器,设置页触发一次完整下载+安装,`getUriForFile` 不抛异常、安装器被拉起; c) 无法真机时,写一个 Robolectric 测试断言 `FileProvider.getUriForFile` 对 `filesDir/updates/*.apk` 可解析。
- **通过标准**:应用内点"立即安装"能拉起系统安装器。

### 1.2 CrashHandler 保留逻辑写反(I2)
- **改动**:`CrashHandler.kt:45-47` 改为 `sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }`。
- **验证**:新增单元测试:临时目录造 21 个不同 lastModified 的假 crash 文件,跑清理逻辑,断言"最新 20 个在、最旧的被删"。用 `CrashHandler` 提取一个可注入目录的内部函数以便测试。
- **通过标准**:测试过 + `exportViaEmail` 导出的第一条日志永远是最新一次。

### 1.3 SearchViewModel 吞取消异常(I2)
- **改动**:`SearchViewModel.kt:69` catch 块首行加 `if (e is CancellationException) throw e`(import `kotlinx.coroutines.CancellationException`)。
- **验证**:新增单元测试:`searchAllStream` 用慢 fake,连续两次 `search()`,断言 `_error` 保持 null、`_isSearching` 在新 job 结束前为 true。
- **通过标准**:测试过;真机连续快速搜索不再闪"搜索失败"。

### 1.4 preloadedSources 加同步(I2)
- **改动**:`PlayerManager.kt:125` 用 `java.util.Collections.synchronizedMap(...)` 包裹(与同文件 `trackRegistry` 同款)。
- **验证**:编译 + 现有 `QueueNavigatorTest`/单测回归;代码审查确认 IO 写入点(:589/:601)与 Main 读取点(:303/:337)都走同一包装实例。
- **通过标准**:构建+单测通过,所有访问点经 synchronizedMap。

### 1.5 首次授权后启动驾驶检测(I2)
- **改动**:`MainActivity.kt:41-43` 权限回调中,`ACCESS_FINE_LOCATION` 被授予时调 `container.drivingDetector.start()`。
- **验证**:模拟器:全新安装 → 首启弹权限 → 允许 → 日志确认 `LocationManager` 回调注册(release 包 + `adb logcat | grep DrivingDetector`)。
- **通过标准**:允许权限后**不重启 App** 也能自动进入驾驶模式。

### 1.6 GdStudio 熔断器误计取消(I3 的兜底链路)
- **改动**:`GdStudioSource.kt:70` catch 块首行加 `if (e is CancellationException) throw e`。
- **验证**:单元测试:取消中的 resolveFor 不递增 `consecutiveFailures`(需把熔断计数器做成可注入/可见)。
- **通过标准**:测试过。

### 1.7 缓存 key 空集与 null 折叠(I3)
- **改动**:`SourceManager.kt:60` key 对 `enabledPlatforms` 做 `null → "all"`, `emptySet() → "none"` 显式映射。
- **验证**:单元测试断言三个 key 互不相同且稳定。
- **通过标准**:测试过。

### 1.8 回归脚本里的猫耳播放解析
- **改动**:先在 `scripts/test_netease_weapi.py` 风格下用 Python 复现猫耳 `Expecting value` 报错,定位是接口改版还是响应空;若接口改版,修 `MaoerSource` 对应解析;若上游已死,在 README 标记并将脚本该项降为 warn。
- **验证**:脚本该项恢复 ✅(或明确降级为 warn 并记录原因)。
- **通过标准**:脚本 exit 0 且猫耳状态是 ✅ 或有据可查的 warn。

### 1.9 JsonEscapes 不可见字符(I5,防未来)
- **改动**:`JsonEscapes.kt:11` 的字面 form-feed 字符改写为 `'\u000C'`。
- **验证**:`od -c` 确认文件中不再有裸 0x0C 字节;跑一次 `test_netease_weapi.py` 确认 weapi 加密行为不变。
- **通过标准**:两个验证都过。

### 1.10 死权限清理
- **改动**:删除 Manifest 中 `BLUETOOTH_CONNECT`(全项目零蓝牙调用,已 grep 证实)。
- **验证**:构建通过 + `aapt dump permissions` 输出无该项 + 车机冒烟(方向盘按键经 MediaSession,不依赖此权限)。
- **通过标准**:三项验证都过。

**阶段 1 出口**:版本号升 `3.3.1` / versionCode 21,`assembleRelease` 产出 APK,完成阶段 0.3 门禁清单(含**应用内更新 v3.3.0 → v3.3.1 实装成功**,用这一版验证 1.1 的修复)。

---

## 阶段 2:失败不进缓存(I3,收益最大的结构性修复)

### 2.1 ApiCache 语义升级
- **改动**:`ApiCache` 增加失败感知的 `getOrPut`:block 抛异常时不写缓存并向上抛;同时把 SourceManager 三处(`searchAll`、`getRecommendedPlaylists`、`getPlaylistTracks`/`getPlaylistSquare`)的 `runCatching{...}.getOrDefault(emptyList())` 移出缓存 block——失败向上传播为"本次无结果但不缓存",调用方 UI 显示可重试错误而非空列表。顺带修 ApiCache 自认的锁回收竞态(持有期间不 remove)。
- **验证**:单元测试三个用例:block 抛异常 → 缓存无条目、异常上抛;block 成功 → 正常缓存;同一 key 并发 → 只执行一次 block。
- **通过标准**:三测试过;真机飞行模式开→搜索→显示错误可重试;关飞行模式重试 → 立即出结果(不再命中 5 分钟假缓存)。

### 2.2 歌词负缓存与网络失败分离(I4 的歌词面)
- **改动**:`LyricRepository.kt:41-50`:网络异常时若磁盘已有旧歌词直接返回旧值,不覆盖;只有"业务性确认无歌词"才写空负缓存。
- **验证**:单元测试:fake source 抛异常 → 旧缓存保留;返回 null(真无歌词)→ 写负缓存。
- **通过标准**:测试过。

### 2.3 错误分类最小版(I3 的播放链路)
- **改动**:`SourceManager.getMediaSource` 的 fallback 前区分:原平台**网络异常** → 直接放弃本次(不触发 7 平台风暴),由 PlayerManager 提示重试;**确认无源** → 现有跨平台 fallback。用一个轻量 `SourceException(kind)` 类型。
- **验证**:单元测试:超时场景不调用 searchAll;无源场景调用且 `isSameSong` 过滤生效。
- **通过标准**:测试过;真机断网播歌不再产生 10+ 请求风暴(logcat 计数)。

---

## 阶段 3:PlayerManager 生命周期(I2)

### 3.1 release 降级为 disconnect
- **改动**:`MainActivity.onDestroy(isFinishing)` 改调新的 `playerManager.disconnect()`——只 `MediaController.releaseFuture` + 停进度更新,**不 cancel scope、不清 trackRegistry/收藏等状态**;下次 MainActivity onCreate 时 `connect()` 幂等重建 controller。`onPlayerError`/`refreshAndRetry` 顺带修:retryJob 执行时校验目标曲目仍是当前曲目,`next()/previous()` 先取消 retryJob。
- **验证**:
  a) 单元测试:disconnect → play → 重连 → play 全链路(fake controller);
  b) 真机:根页返回 → 进程在(`adb shell pidof com.carmusic`)→ 重进 → 播放正常、队列仍在;
  c) 重现原 bug 场景:`adb shell am force-stop` 对照组。
- **通过标准**:重进 App 后点歌即播、方向盘按键有效、队列高亮正确。

---

## 阶段 4:清理器加固(I4)

### 4.1 runIfDue 原子化
- **改动**:`ContentCleaner.kt:56` 用 `Mutex` 或 `compareAndSet` 语义把 check-then-set 变原子。
- **验证**:单元测试:并发两次 runIfDue,清理 body 只执行一次。

### 4.2 挂账集合 merge 而非覆盖
- **改动**:死链/黑名单写入从"本轮结果整体覆盖"改为"按 trackId 合并 + 各自通过计数";黑名单移出条件 = 连续 2 轮探测通过(而不是"没探到就放出来")。
- **验证**:单元测试覆盖四个时序:第 1 周失败→第 2 周失败→删;第 1 周失败→第 2 周成功→留且计数清零;被过滤未探测的挂账不被清;自动+手动并发不丢挂账。

### 4.3 超时与真死链分账
- **改动**:`TRACK_PROBE_TIMEOUT_MS` 超时单独计数(如 `timeoutStreak`),连续两轮**超时为主**的周期跳过删除并提示"弱网,本轮跳过"。
- **验证**:单元测试:两轮全超时 → 不删;两轮明确 404 → 删。

---

## 阶段 5:门禁与发版流程固化(I5)

- 阶段 0 的三件事到此已经验证;本阶段把它们接进流程:
  - 回归脚本作为发版红线:exit 1 → 不发版。
  - `-PintegrationTests` 真实 API 测试发版前必跑。
  - 门禁清单(`docs/RELEASE_CHECKLIST.md`)每版走完归档一份执行记录(日期 + 各项结果)。
- **验证**:下一次真实发版(v3.4.0)全程按清单执行,归档记录存在且完整。

---

## 阶段 6:架构减法(下一次加平台**之前**做)

> 触发条件:计划接入第 9 个音源时启动。在此之前不做,避免无谓的回归风险。

1. **JSON 解析统一**:全部 provider 的 per-item 解析改用 `JsonHelpers` 的 opt 系列(修掉 8 处"单字段 JsonNull 杀整轨"病灶:Netease:61、QQ:82/144/394、Kugou:60、Migu:49/207、Maoer:49/63、Jamendo:178)。
2. **HTTP 统一**:`ProviderHttp` 收编裸 `execute()`(QQ:296、Kugou:186、Kuwo:79、Migu:104),统一超时、JSONP 剥离、错误分类日志(403/429/超时/DNS 分开记);全局 client 加 `callTimeout`;流式搜索超时改用派生短 callTimeout client(抄 GdStudioSource.kt:32-34 现成方案)。
3. **复制粘贴收敛**:`norm()` 两份合一、协议补全三处合一(补上 Maoer 的 `//` 前缀)、分页换算责任写进 MusicSource 接口注释。
4. **明文 API 收口**:酷狗 `mobilecdnbj.kugou.com`、酷我 `kbangserver.kuwo.cn` 的数据接口尝试 https,失败则记录并在 NSC 注释中如实标注。
- **验证**:每小项 = 现有回归脚本全绿 + 针对性单元测试;整体 = 真机全平台冒烟(README 清单 10 项)。

---

## 阶段 7:运维面(一次性,非代码)

1. **签名钥匙异地备份**:`release.keystore` 是每周更新身份的唯一凭证,导出一份到独立存储(网盘/U盘各一),与仓库物理分离;同时把 `carmusic2026` 换成长密码。
2. **UpdateManager 小修**(随阶段 2/3 任一发版捎带):version.json URL 强制 https;取消下载后删除半截包;`installApk` 改 `ACTION_INSTALL_PACKAGE` 并在跳授权页时给用户 Toast 提示;debug 构建下 FileProvider authority 用 manifest 占位符对齐。
3. **README 修正**:驾驶模式特性标注"需定位权限,首次授权后生效"(1.5 修复后仍建议保留说明);更新机制补一句"签名一致性由系统安装器保证,sha256 防半截包"。

---

## 执行顺序与依赖

```
阶段0(验证基建) → 阶段1(热修,含 v3.3.1 发版验证 1.1)
    → 阶段2(缓存语义) → 阶段3(生命周期) → 阶段4(清理器)
    → 阶段5(门禁固化,随下一次真实发版) 
    → 阶段6(架构减法,加第9个平台前触发)
阶段7 与阶段 2/4 可并行穿插。
```

每完成一个 item:勾掉 + 记录验证证据(命令 + 输出摘要)。**一条铁律:验证不通过的 item 不进入下一阶段。**
