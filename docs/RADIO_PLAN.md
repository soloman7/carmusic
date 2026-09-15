# 听电台方案 v2(local-first 重构版,2026-09-15)

> v1 被评"调研 A,产品 C+,架构 D"。本版按批判逐条重构:架构从 API-first 反转为 local-first,
> PlaybackTarget 显式类型化,模式仲裁先写状态机再动手,NSC 决策摆账不喊口号,直播流补全技术规格,
> 浏览结构砍到三屏,工作量按真实耦合重估。v1 数据结论保留,架构结论全部作废重写。

## 一、调研结论(实测数据,2026-09-15,中国 PC 网络)

| 项 | 数值 |
|---|---|
| 项目 | RadioBrowser(radio-browser.info),服务端开源,免费 API 无 Key |
| 全库 | 58,576 台,6,389 标记失效 → **~52,000 可用**;242 国 |
| 中国 | stationcount 2,321;hidebroken 实拉 **2,073 台**,64%(1,326)为 https |
| API 镜像 | **仅 de1(德国)可达**(400~1400ms);nl1/at1/fi1/all 连接被重置 |
| seed 包实测 | CN 全量 + 全球 top1000 = **3,073 台,裁剪后 1,298KB JSON(gzip ~320KB)** |
| 增量端点 | `/json/stations/changed?lastdays=N` 存在(200);实现时需确认返回含 url 字段,缺则回退全量分页 |
| 拉流抽测 | CNR-1(HLS)✅、怀集音乐之声(MP3)✅、CCTV-13(HLS 播放列表正常)✅ |
| 数据质量警示 | CN 的 `state`(省份)字段质量差:`/json/states` 过滤参数无效,社区填写稀疏混乱——**"本省"浏览必须客户端侧容错** |
| HLS | media3-exoplayer-hls 已在依赖,511 个 CN HLS 台零新依赖可播 |

**未验证且 v2 不再掩盖**:de1 在车机蜂窝网络下的可达性(4G 路径与 PC 不同)。local-first 架构使它从"产品生死"降级为"数据新鲜度"——这正是重构的意义。

## 二、架构决策

### D1 · local-first:数据搬回家,API 只是更新器

- Room 新表 `radio_stations`:**CN 全量(2,073)+ 全球 top 1,000 ≈ 3,100 台**随 APK 内置(`assets/radio_seed.json`,gzip ~320KB),首启导入 Room。
- 浏览/搜索/分类/收藏**全部查本地**,零网络依赖。等红灯点开电台页 = 一次本地查询,无转圈。
- 同步:启动延迟任务,距上次同步 >7 天且 API 可达时,`/stations/changed?lastdays=14` 增量 upsert;`lastcheckok=0` → 本地 `hidden=1`(内置包与收藏台共用同一自愈管道,**消灭 v1"精选包静默腐烂"问题**)。API 不可达 → 静默跳过,下个窗口重试,用户无感。
- 顺带修正 v1 的两个错误概念:①"精选 200 台"是 local-first 的缩水版,直接做全量;②电台点击上报 `/json/url/{uuid}` 采用 fire-and-forget(节流、仅播放时),不污染自己依赖的 topclick 排序。

### D2 · PlaybackTarget 显式类型,五处副作用按类型分发

`sealed class PlaybackTarget { Music(track) | Radio(station) }`,PlayerManager 内的隐式假设收敛为显式分支,**禁止 `mediaId.startsWith("radio:")` 散落判断**:

| 副作用点 | Music | Radio |
|---|---|---|
| `onMediaItemTransition` → history 写入 | ✅ | ❌ 跳过(电台不污染音乐历史) |
| `schedulePreloadNext` | ✅ | ❌ 跳过(直播流无"下一首") |
| `onPlayerError` → refreshAndRetry(音乐重签链) | ✅ | ❌ 走电台重起流语义(见 D5) |
| `persistNow`(onIsPlayingChanged/transition 两入口) | 队列+进度 | 只写"最后电台"(单行,无进度) |
| DeadTrackLedger 出账(STATE_READY 钩子) | ✅ | ❌ 跳过 |

`restoredSnapshot` 恢复逻辑按 target 类型分流;电台不进 `trackRegistry` 的音乐语义区(登记表加类型字段或旁路表)。**工作量按此重估,不再是"一个 playRadio 函数"。**

### D3 · 模式仲裁状态机(M2 动工前定死,不即兴)

```
mode ∈ {MUSIC, RADIO},持久化于 DataStore(lastPlaybackMode)
切换:用户播歌 → MUSIC;用户选台 → RADIO;手动切换即时生效
方向盘 NEXT/PREV:MUSIC → QueueNavigator;RADIO → 收藏台循环(按 sortOrder)
方向盘 PLAY/PAUSE:当前 mode 内 toggle;冷启动(进程被杀):
  mode=MUSIC → 现有快照恢复链;mode=RADIO → 自动起播最后收听的台
点歌/选台的"自动回语义"不存在歧义:最后一次用户主动选择即当前 mode
```

- **收藏台排序**:显式 `sortOrder` 整数列 + 台目详情里"上移/下移"按钮(车机无拖拽)。新收藏追加末尾。
- **收藏 1 台**:NEXT = 本台重新起流(等效"重连",直播流语义下合理)。
- **收藏 0 台**:RADIO 循环 no-op;电台页默认落在"本省"页引导收藏。
- **冷启动直接按方向盘键**:沿用 v3.4.3 的 playRestoredIfAny 链,RADIO mode 下起播最后电台。

### D4 · NSC 决策:放开 base cleartext(B 方案),把账摆在明面

v1"https-only + 灰显不展示"自相矛盾且教条。v2 算账:

- **保护对象**:公共广播音频流。无凭据、无个人数据、内容本身免费公开——机密性/完整性价值 ≈ 0。
- **代价**:https-only 砍掉 36% 中国台(2,073 → 1,326),砍掉的恰是地市交通台/本地新闻台——车载电台的核心价值。
- **可维护性**:几千个电台随机域名,NSC domain-config 白名单路线不可维护(v1"后续按域加白"走不通,作废)。
- **决策**:采用 B——`base-config cleartextTrafficPermitted="true"`,http 台正常展示(协议仅作徽标,不灰显不隐藏)。代码层纪律不变:所有 API 调用保持 https 字面量,UpdateManager 已强制更新链路 https。**残余风险如实登记**:车载蜂窝网络中间人可注入/篡改广播音频流——攻击价值趋近于零,私家自装 APK 威胁模型下可接受。音乐流 CDN 既有白名单保留(无害冗余)。

### D5 · 直播流技术规格(v1 空白处补全)

- **LiveConfiguration**:电台 MediaItem `setLiveConfiguration(targetOffsetMs=10_000, minPlaybackSpeed=0.95, maxPlaybackSpeed=1.02)`——追 live-edge 不漂移、不频繁 rebuffer;歌曲 MediaItem 不设置(点播语义)。
- **缓冲**:ExoPlayer 全局 DefaultLoadControl 保持点播调优;直播起播延迟靠 LiveConfiguration + 连接态 UI 兜(车机 4G 冷启 2~10s,UI 明示"连接中"而非假进度)。
- **错误语义 = 重新起流,不是同位置 retry**:失败 → 用本地 uuid 重查 API 刷新地址 → 更新 Room → 重试一次 → 仍失败则本地标记 `lastchecked=0` 入复验队列 → 驾驶态自动跳下一收藏台,非驾驶态提示"该台可能已下线"。v1 的"url↔url_resolved 互换"作废:url_resolved 是上次 check 的快照,而 ExoPlayer 本就自动跟 302,两者运行时等价。
- **流量**:128kbps ≈ 56MB/h。设置页新增"电台码率上限"(不限/128/96/64kbps,过滤本地库);播放卡常显"≈NN MB/小时"。
- **UA**:全局统一 `carmusic/<version>`(BuildConfig.UA,API 与 OkHttpDataSource 已统一),符合 RadioBrowser 可识别 UA 要求。
- **click 上报**:起播成功后 GET `/json/url/{uuid}`,fire-and-forget、每台每小时最多一次。

## 三、产品设计(M1 三屏封顶)

1. **收藏(默认页)**:排序可调,空态引导。
2. **本省**:省份选择器 = **GPS 最近质心自动推荐**(内置 34 省质心表,离线)+ 手动改;列表 = 本地库 `state` 字段过滤 ∪ 台名/tag 包含省名匹配(容忍脏数据,并集去重)。countries/全球分类整个砍掉,全球热门降级为搜索页一个入口。
3. **搜索**:台名关键字,本地 LIKE。驾驶态(`isDriving=true`)下搜索框隐藏——等红灯时检测器仍判定驾驶,这是有意的保守(电台台名输入比歌曲搜索更依赖打字,盲操作不可行);语音整合依赖 DiLink 语音助手开放能力,M3 前不动,先如实标注 out-of-scope。

**布局修正**:废除 v1"左侧列表+右侧常驻收听卡"的桌面双栏,改为全宽列表 + 底部 compact 收听条(LIVE 徽标 / 台名 / codec·码率 / ≈MB/h / 停止)。入口:PlayerScreen 控制排 + DriveModeScreen 大按钮(M2)。

## 四、数据与同步规格

- Room 4→5:`radio_stations`(uuid 主键,`hidden` 标记,索引:country/state/name;name 查询 3.1k 行 LIKE 无压力)+ `radio_favorites`(uuid 主键,`sortOrder`,`addedAt`)。schema JSON 照例入库。
- seed 版本化:assets 内 `radio_seed.meta.json`(日期+台数),导入幂等(按 stationuuid upsert)。
- 增量同步:`/stations/changed?lastdays=14` → upsert(url/name/lastcheckok 等);实现时若该端点响应缺 url 字段,回退为"全量分页重拉 CN+top"并写进注释。同步与收藏台复验共用同一延迟任务,全程避让播放(复用 awaitNotPlaying 带超时的既有模式)。

## 五、分期与工作量(v2 重估)

| 阶段 | 内容 | 规模 |
|---|---|---|
| M1 核心可用 | Room 迁移+seed 导入+同步器;PlaybackTarget 类型化改造(动 PlayerManager 五处);电台三屏 UI+收听条;播放/错误语义/流量显示;NSC B | **~1,200 行** + 2 表 + seed 资产 |
| M2 驾驶与仲裁 | 模式仲裁状态机落地(冷启动/方向盘路由);DriveMode 电台大按钮;收藏排序上下移;会话恢复(最后电台) | **~350 行** |
| M3 自愈与扩展 | 收藏台 lastcheckok 周期复验(挂账复用);Auto 浏览树电台节点;tag 分类页(按需) | ~250 行 |

## 六、验证计划

- **PC 探针固化** `test_radio_api.py`:de1 健康、CN 台数、seed 一致性、抽 5 台拉流——定位是"数据生产侧"验证,**不宣称覆盖车机网络**(v1 的错)。
- **车机实网 = M1 出口必要项**:de1 同步是否成功、本地浏览零网络可用性(飞行模式开电台页)、3 台实播(含 1 HLS)、断流恢复(开关飞行模式)、流量读数合理性。de1 车机不可达时功能仍须完整可用(这正是 local-first 的验收标准)。
- **单测**:seed 导入幂等/字段容错;PlaybackTarget 五处分发(电台不写历史/不预载/不进 ledger);收藏循环边界(0/1/N 台);省份匹配并集去重;码率过滤。
- **回归扩展**:收藏台全集跑 lastcheckok 复验(本地库驱动),替代 v1 的"固定 5 台"一次性快照。

## 七、风险登记

| 风险 | 等级 | 对策 |
|---|---|---|
| de1 车机不可达 | 中(已降级) | local-first:仅影响数据新鲜度,功能完整可用;seed 内置 |
| CN state 字段脏 | 中 | 省份匹配 = state∪名称∪tag 并集;选择器可手动改;实测后必要时改为"城市 tag"方案 |
| 电台普遍失效(全库 11%) | 中 | hidebroken 过滤 + 增量同步 hidden 标记 + 播放失败自动跳下一收藏台 |
| PlayerManager 类型化改造引入歌曲回归 | 中 | 单测锁定五处副作用;车机冒烟歌曲链路全过 |
| 明文流 NSC 放开 | 低(已算账) | D4 决策记录;API 层代码纪律不变 |
| 语音搜台(驾驶态刚需) | 已知缺口 | 依赖 DiLink 语音能力,M3 前不动,如实标注 |

—— v2 完稿。M1 待批准动工,照旧:每步验证、门禁全绿、应用内更新交付。
