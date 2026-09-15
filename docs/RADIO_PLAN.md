# "听电台"功能调研与方案(2026-09-15)

## 一、调研结论

目标项目确认为 **RadioBrowser(radio-browser.info)**——社区共建的全球网络电台数据库,服务端开源(GPL),数据 CC 授权,免费 API 无需 Key。GitHub: RadioBrowser/RadioBrowser(服务端),多语言官方客户端库(Java/Python/Rust/JS)。

### 实测数据(2026-09-15,中国 PC 网络直连)

| 探针 | 结果 |
|---|---|
| 数据规模 | 全库 58,576 台(6,389 台标记失效 → **~52,000 可用**),242 国,661 种语言 |
| 中国电台 | **2,321 台**(hidebroken 后按 votes 拉取 1,500 条分析) |
| API 可达性 | **de1(德国)✅ 通**,延迟 400~1400ms;nl1/at1/fi1/all ❌ 连接被重置 |
| https 流占比(CN) | **60%**(907/1500);HLS(m3u8) 511 条;codec 以 MP3 为主(977),ExoPlayer 直接嗅探 |
| 拉流实测 | CNR-1 中国之声(HLS)✅、怀集音乐之声(MP3)✅、CCTV-13(HLS)✅(脚本误报,播放列表正常取到) |
| 头部台 | CCTV-13、CNR-1/2 中国之声/经济之声、凤凰卫视系列、各地市台,votes 最高 17k |

### 可行性判定:**可行**,三个设计决策决定成败

1. **API 单点问题**:四个镜像只有 de1 从国内可达(其余被重置),`all.api` 轮询会撞死镜像。→ 运行时只打 de1 + **内置精选电台包兜底**(App 断网/接口被墙时热门台照听不误)。
2. **明文流问题**:40% 中国台是 http-only,而 NSC 目前只对 4 个音乐 CDN 域名开明文。→ **默认只放 https 台**(CN 约 1,400 台,全球热门台绝大多数 https),不为电台全面放开明文;后续若确有需要,再加主流广播 CDN 的域名白名单。
3. **HLS 已具备**:media3-exoplayer-hls 在依赖里,511 个 CN HLS 台 + 大量海外台直接可播,零新依赖。

## 二、产品设计

- **入口**:主界面新增"电台"页(nav route `radio`),横屏双栏:左侧分类/列表,右侧"正在收听"卡片(复用播放控制)。
- **浏览结构**(全部来自 API 现成字段):
  - 热门台:`/stations/topvote` 与 `topclick`(全球 + 中国 tab)
  - 分类:`/tags`(新闻/音乐/交通/相声/财经…中文 tag 直接可用)、`/countries`、CN 按省(`state` 字段)
  - 搜索:台名关键字(电台搜索是浏览型,不像歌曲搜索怕打字,驾驶模式外开放)
- **收藏**:Room 新表 `radio_favorites`(uuid 主键 + 冗余 name/url/logo),列表一键收藏;**方向盘上一首/下一首 = 收藏台间循环切换**(电台场景下比切歌更常用,且是纯盲操作)。
- **驾驶模式**:DriveModeScreen 加"电台"大按钮;电台页在驾驶态下隐藏搜索框、保留收藏+热门两个大按钮列表。
- **播放语义**(与歌曲的关键差异):直播流无时长/不可 seek → 进度条替换为"LIVE"徽标 + 播放时长累计;`onPlayerError` 不走歌曲的重签链,改为"同台 url ↔ url_resolved 互换重试一次 → 提示失败";EQ/音频焦点/becoming noisy 全部自动生效(同一 ExoPlayer)。
- **会话恢复**:只持久化"最后收听的台 + 收藏列表",重启后一键回听(不存队列/进度)。

## 三、技术设计

- **数据层**:`RadioRepository`——API 客户端(de1,~1 req/s 节流,OkHttp 共享 client)+ Room `radio_favorites` + **精选包 `assets/radio_curated.json`**(首次内置 CN 头部 ~100 台 + 全球 ~100 台,字段与 API 对齐);浏览结果走 ApiCache(电台列表 TTL 30min,tag/国家列表 24h)。
- **UI 层**:`RadioScreen` + `RadioViewModel`(列表/分类/收藏三态);平台色沿用主题;封面用 station favicon(Coil 现成)。
- **播放层**:PlayerManager 增加 `playRadio(station)`——独立于歌曲队列:`_queue` 不动、`persistNow` 跳过、`currentTrack` 显示台名(/mediaId 用 `radio:<uuid>` 与歌曲 trackId 天然不冲突);返回歌曲页点歌自动回到歌曲会话语义。
- **NSC**:维持 `cleartextTrafficPermitted=false` 基线,电台 https-only 过滤在 Repository 做;http-only 台在 UI 灰显不展示(数据里有 `url` 协议可判)。

## 四、分期与工作量

| 阶段 | 内容 | 规模 |
|---|---|---|
| M1 核心可用 | 电台页(热门/中国/分类/搜索)+ 播放/停止 + 收藏 Room + 精选包兜底 | ~800 行 + 1 张表 + 1 个 JSON 资产 |
| M2 驾驶集成 | DriveMode 电台入口 + 方向盘收藏台切换 + 会话恢复 | ~150 行 |
| M3 自愈 | 收藏台周期性 lastcheckok 复验(复用清理器骨架)+ Auto 浏览树电台节点 | ~200 行 |

## 五、风险与对策

| 风险 | 对策 |
|---|---|
| de1 镜像被墙(单点) | 精选包兜底;收藏台冗余完整播放信息,断网 API 也能播 |
| 电台失效率高(全库 11% broken) | 只取 hidebroken=1;播放失败提示"该台可能已下线"并自动跳下一收藏台(电台场景合理默认) |
| 明文台被 NSC 拦 | https-only 默认过滤;不降级全局明文安全策略 |
| m3u8 播放列表里嵌 http 分片(CN 台可能) | 实测 China HLS 台分片普遍同源;若遇到,HLS 分片明文属 ExoPlayer 数据源层,如出现再按域加白(先验证再动 NSC) |
| 台名/编码混乱(数据库社区维护) | 列表去重(名+url);展示 codec/bitrate 供用户判断 |

## 六、验证计划(M1 出口标准)

- 回归脚本新增 `test_radio_api.py`:de1 可达、CN 台数、抽 5 台拉流(与本次调研同款探针固化)。
- 单测:RadioRepository 解析/过滤(https-only、去重、精选包加载)。
- 车机冒烟:热门中国台播放 5 个(含 1 个 HLS)、收藏 2 台重启仍在、驾驶模式大按钮进电台、方向盘切收藏台、播放中熄屏不断流、导航打断 ducking 生效。

—— 调研阶段完成,M1 待批准后动工。
