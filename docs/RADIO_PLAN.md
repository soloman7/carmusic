# 听电台方案 v5(合成一致性收口版,2026-09-15)

> v4 每个数字单独有实测背书,组合起来不自洽(C1 一行 URL 三种读法两种失败;C2 的 73s 被 6 行外的
> 60s 预算判死;C3 预算写在单次采样上被方差击穿)。v5 制度终态:**断言纪律 = 单端点语义 + 合成一致性**
> ——预算必须 ≥ p95(所管辖请求的实测分布),查询形态必须是探针真正跑过的形态。〇表从"依据"扩展到
> "实现规格依据"由此完成。五轮轨迹留档:v1 架构错 → v2 地基死 → v2.1 加倍下注 → v3 规格塌 →
> v4 单点实测无合成 → v5 合成一致。病一层比一层浅,收敛形状成立。

## 〇、v5 探针记录(修正后的实现形态,端到端,2026-09-15 深夜,可复现)

| 探针 | 断言 | 实测 | 判定 |
|---|---|---|---|
| V5-1 分页形态端到端 | `limit=500&offset=k*500&order=stationuuid` × 5 页:每页 ≤150s;Σ行数 ≥ 2322×0.8;跨页 uuid 重叠 = 0 | 35.1/53.9/50.4/39.2/26.8s,**2,322 台,重叠 0,总 205s** | ✅ **C1 修正形态成立**——这是 v4 写而未跑的形态 |
| V5-2 N3 方差复现 | 同页重拉 ≤150s | 页0 35.1s → 49.4s(**1.41×**;审查者测得 2.1×) | ✅ 150s/页(p95=单点×3)在方差下成立 |
| V5-3 C2 修正形态 | topvote `limit=250&offset=k*250` × 4 页 | 22.2/29.0/24.7/21.6s,恰 1,000 行,最慢 29.0s | ✅ C2 修正成立 |
| C6 确定性排序 | order=stationuuid 被接受且轮内稳定 | 服务端接受,零重叠 | ✅ 续传正确性来自约定 |
| C11 数据质量 | — | 页0 实测 2 个纯空白台名 | ✅ UI"未命名电台"兜底 + LIKE 前 trim 入规格 |

## 一、审计表(第五轮批判 → v5 落点 → 证据)

| # | 批判 | v5 落点 | 证据 |
|---|---|---|---|
| C1 | 查询形态改正 | D1-a:`limit=500&offset=k*500&order=stationuuid`(v4 的 `limit=5000,offset=…` 矛盾句删除) | 〇 V5-1 端到端 |
| C2 | topvote 分页或独立预算 | D1-b:`limit=250&offset=k*250` × 4 页(选分页:白送断点续传) | 〇 V5-3 |
| C3 | 预算×3 + 页级重试 | D1:150s/页(p95 思维;实测分布 27~54s、方差 1.4~2.1×),页失败重试 ×2(upsert 幂等零成本);**最终预算由车机逐页耗时分布锁定(M1b 出口新增)** | 〇 V5-1/V5-2 |
| C4 | 收藏页豁免过滤 + 徽标态 | D5:三态过滤仅作用于浏览列表;收藏页永远全量显示 + 状态徽标("最近失败·点击重试"/"已失效"),循环跳过但可见,点徽标 → 清零重试(可见才可点,v4 自相矛盾消除);**回退链终底改为"可见性过滤后的第一名"**(防终底是死台) | 逻辑修正 |
| C5 | 复验队列落户口 | M3:单一 byuuid 批量 worker,三类输入(收藏台/挂账到期台/消失嫌疑台),输出 deleted 确认或嫌疑解除;队列上限 500 FIFO | — |
| C6 | 排序写死确定性 | D1:order=stationuuid(约定而非观测) | 〇 V5-1 |
| C7 | 行数断言分母本地化 | D1:分母 = max(上次成功同步行数, seed 台数),stationcount 轻调用校准可选(5s/10s),失败用缓存分母——哨兵自身无运行时单点 | — |
| C8 | seed 口径统一 | §四:seed 改用与同步相同的**不带 hidebroken** 语料(M1b 构建时以同一查询重新生成),消灭 249 行凭空 delta | — |
| C9 | click 上报超时档 | D5:归轻调用档(5s connect/10s total),起播成功后异步发,绝不延迟起播 | — |
| C10 | 播放中断同步语义 | D1:用户点火播放 → 页间暂停(逐页事务白送),下窗口续传 | — |
| C11 | 空白台名 | UI"未命名电台"兜底;搜索 LIKE 前 trim | 〇 V5-1(实测 2 个) |
| 小项 | M1b 重估;单调性真执行;断言清单补完整性 | M1b 维持 ~1,000 行(v4 已含本轮全部增项);〇表含单调性行(v4 已执行);完整性断言(行数下限+Content-Length/末字节)在 §七 | — |

## 二、调研结论(数据更新至 2026-09-15 深夜)

| 项 | 数值 |
|---|---|
| 全库 | 58,585 台(活);~52,000 可用 |
| 中国 | 2,322 台(分页实拉,order=stationuuid,零重复);64% https;bitrate=0 占 89%;空白名 2 台 |
| 镜像 | de1/de2 可达(轮转制);其余全灭 |
| changed | 冻结 243 天,禁用 |
| 分页形态 | **每页实测 27~54s(PC,方差 1.41~2.1×);topvote 页 ≤29s;总 205s** |
| seed | 3,073 台 / 1,298KB(gzip ~320KB);M1b 起口径与同步统一(含失效行) |
| CN∩top1000 | 9(墓碑只认 byuuid 的前提) |
| 拉流抽测 | CNR-1(HLS)/怀集(MP3)/CCTV-13(HLS)✅;直播流无视 Range(P4,v4 结论维持) |

## 三、架构决策

### D1 · 同步 = 确定性分页全量重拉 + 三态 diff(v5:形态经端到端验证)

```
周期任务(距上次 >7 天 && 避让播放 && 镜像轮转可用):
  a. CN 语料:/stations/search?countrycode=CN&limit=500&offset=k*500&order=stationuuid
     预算 150s/页(p95 = 单点实测×3);页失败重试 ×2(upsert 幂等);
     完整性断言:Σ行数 ≥ max(上次成功同步行数, seed 台数)×0.8,不过则本轮作废
     (分母本地可得;stationcount 轻调用校准可选,失败用缓存分母)
  b. 全球热门:/stations/topvote?hidebroken=true&limit=250&offset=k*250(4 页)
  c. 三态 diff(health / deleted / localDeadUntil 三列互斥):
     upsert a∪b 全字段;lastcheckok → health;
     消失嫌疑(本地有而 a∪b 无)→ 不 hidden,进复验队列(上限 500 FIFO,见 M3);
     墓碑唯一来源 = 复验 worker 的 byuuid diff 缺失 → deleted=1
  中断语义(C10):用户起播 → 当前页完成后页间暂停,下窗口从中断页续传
  提交:每页独立事务
```

### D2 · PlaybackTarget(维持 v3/v4,M1a 独立发版)

### D3 · 模式仲裁(维持 v3/v4 + C4 回退链修正)

冷启动只读不播(两模式一致);方向盘路由按 mode;收藏 sortOrder+上下移;1 台 NEXT=重起流。
**0 收藏 NEXT 回退链**:最后收听台 → 本省列表头部 → **seed topvote 序中第一个通过可见性过滤的台**(终底不得是 health=down/deleted 台),每层伴随 toast。

### D4 · NSC 决策 B(维持,无变化)

### D5 · 直播流规格(v5:C4 过滤作用域修正)

- **可见性过滤作用域(核心修正)**:`health==down || deleted || now<localDeadUntil` 仅作用于**浏览列表**(本省/搜索/热门)。**收藏页永远全量显示**:异常台带徽标("最近失败·点击重试"/"已失效"),循环切换跳过但可见,点徽标 → 清零 localDeadUntil 立即重试。用户收藏永不"凭空消失"。
- **错误恢复(维持 v4 本地优先)**:失败 → 本地 url 重起流一次(1s 退避)→ localDeadUntil 挂账(24h/72h/7d 指数退避)→ 驾驶态跳下一可见收藏台。API 不在恢复路径;后台复验是唯一 API 刷新点。
- **后台复验 worker(M3,C5 落户口)**:单一 byuuid 批量任务,输入三类(收藏台全集/挂账到期台/消失嫌疑队列),输出 deleted 确认或嫌疑解除(嫌疑解除 = health 刷新 + 移出嫌疑队列);队列上限 500 FIFO。
- 码率(维持 v4 降级表述);LiveConfiguration 仅 HLS/DASH;预热已移除,M2 基线后仅允许零流量形态(断言字节≈0);click 上报归轻调用档、起播成功后异步(C9);首启导入骨架态/失败重试(维持)。

## 四、数据与同步规格(单一权威 = D1)

- Room 4→5:三列 health/deleted/localDeadUntil + 索引 country/state/name。
- **seed 口径统一(C8)**:M1b 构建时以与同步完全相同的查询(含失效行)重新生成 seed 资产;seed 新鲜度规则维持(insert-if-absent;health/deleted/localDeadUntil/url/url_resolved 永不降级)。
- 镜像轮转(维持):健康检查档 5s/10s;语料档每页 5s connect + 150s total。

## 五、产品设计(三屏 + C4 徽标态)

收藏(默认页,全量显示+徽标)/ 本省 / 搜索(浏览态,受可见性过滤);全宽列表 + 底部收听条("码率未知"/"—"占位);入口 PlayerScreen + DriveMode(M2)。

## 六、分期(v5)

| 阶段 | 内容 | 规模 |
|---|---|---|
| **M1a(可立即动工)** | PlaybackTarget 类型化 + 五处副作用单测 + 歌曲全回归,独立发版 | ~500 行 |
| **M1b(六项前置已全部落入本版)** | Room 4→5(三列)+ seed(统一口径)+ 确定性分页同步器(150s/页预算、重试×2、行数断言、页间暂停续传)+ 三态 diff + 三屏 UI + 徽标态 + 错误/码率/导入语义 + NSC B | ~1,000 行 |
| M2 | 模式仲裁 + DriveMode 大按钮 + 收藏排序 + **车机逐页耗时分布实测(锁定最终预算)** + 起播基线 | ~400 行 |
| M3 | 复验 worker(三输入 byuuid 批量)+ Auto 节点 + codec 省流探针 | ~250 行 |

## 七、验证计划(v5 增补)

- **断言清单终态(六类)**:字段存在性 / 时间新鲜度 / 单调性 / **完整性(行数下限、Content-Length/末字节)** / 丢弃语义(byuuid) / **合成一致性(预算 ≥ p95 分布;查询形态=探针跑过的形态)**。
- **PC 回归** `test_radio_api.py`:镜像轮转、CN 行数断言、抽 5 台拉流、收藏台全集复验。
- **单测新增**:收藏台在 localDeadUntil 期间仍显示于收藏页(徽标态);topvote 掉榜不 hidden;同步页间暂停-续传(拉到一半开播放不废轮);回退链四层;其余维持 v4。
- **车机实网 = M1b 出口必要项**:同步全程 + **逐页耗时分布记录(用真车数据锁最终预算,PC 数字不再替车机做决定)**、飞行模式降级验收、3 台实播(含 1 HLS)、20 分钟长播、流量读数。

## 八、风险登记(净变化)

| 风险 | 变化 | 对策 |
|---|---|---|
| 车机蜂窝网下 150s/页预算仍可能不足 | 新增 | M1b 出口以车机逐页分布锁预算;分页+续传保证慢而不败 |
| 消失嫌疑队列堆积 | 新增 | 上限 500 FIFO;M3 worker 裁决;超限丢弃最旧(嫌疑台本就 health=旧值可见,误删风险为零) |
| 其余(镜像漂移/state 脏/电台失效/M1a 回归/语音缺口) | 维持 v4 | 见 v4 对策 |

—— v5 定稿:C1~C11 与 N3 全部落地,六项 M1b 前置全部满足,探针覆盖到实现形态本身。**M1a 可立即动工,M1b 随 M1a 发版后进场。**

---

# 电台分类浏览方案(v6 增补,2026-09-16;同日用户定稿)

> 触发:车机实测反馈——①5.8 万台"除了搜索看不见";②推荐位(votes 排序)实测 **36/50 可播**,
> 失败 12 台全为法国国际广播集团(geo-block/被墙),2 台 Opus 流过小待定。
> 苏格拉底四问未获应答,按推荐项执行;**用户已定稿分类结构**(见 D-A 修订):
> "第一大类按照国家,每个国家下面再分成静态分类(流行/摇滚/新闻/古典/爵士/老歌/舞曲/财经/谈话/文艺
> + 交通等中国台名关键词类)。中国的电台按照省份划分。其他方案不变。"
> 即:双轨匹配/clickcount 口序/每分类 top 100/分类卡替代 votes 推荐位四项全部采纳,
> 维度由"平面 12 分类"改为**两级(国家 → 分类;中国 → 省份)**。

## 一、第一性原理

电台页的本质:**把"你现在能听、你想听的台"放到离手最近的地方**。
- 行驶中:收藏 + 本省(已有,不动)。
- 停车探索:分类 = 在不知道台名时按"想听什么类型"发现电台——这正是推荐歌单给歌曲提供的价值,迁移到电台。
- "受欢迎程度"的真实含义不是历史投票(votes),而是**最近有人听**(clickcount)——它同时是"现在能用"的最强代理信号。votes 推荐位 28% 失效率就是反例。

## 二、调研证据(2026-09-16 实测)

| 项 | 数据 |
|---|---|
| 推荐位逐台实测 | hotRank 1..50:**36 可播 / 14 不可播**;失败 12 台全集中在法国国际广播集团(icecast.radiofrance.fr / radioja.fr 主机族),2 台 Opus 流过小待定 |
| tag 现状 | 英文自由标签:pop 6213 / music 5257 / rock 3252 / news 3067 / jazz 1247 / oldies 1448 / 80s 1267…含垃圾 tag("moi merino" 1914);**中文 tag ≈ 0** |
| CN 台 tag | 抽样 200 台,**82% 有 tags**,且为英文:music 56 / news 30 / entertainment 11 / classical 7 / economics 7… |
| CN 台名 | 关键词富矿:含"音乐"50 台、"新闻"14、"交通"7、"经济"6 |
| 排序口径 | clickcount(近期实际收听)vs votes(累计票);news tag 样本显示两者头部不同 |

## 三、设计(四项假设可推翻)

### D-A(修订,用户定稿)· 两级浏览 = 国家 → 静态分类;中国 → 省份

**第一级 = 国家网格**(分类 tab 默认视图):
- 中国固定首位,其余按 SUM(clickcount) 降序,top 30 张卡(长尾小国靠搜索,长尾不设 A-Z 索引——250 国网格在车机上不可扫视)。
- 卡 = 国旗 emoji(ISO 码可计算,零资产)+ 中文名(硬编码 ~80 常用国映射,未映射回退 ISO 码)+ 可见台数。
- **countryCode 为空的台(~1200 台)不进国家网格**,搜索仍可达。

**第二级**:
- 中国 → **省份网格**(34 省级行政区,按可见台数降序)。CN state 字段为邮政罗马音脏数据
  (实测 64 个变体:Kiangsu/Chekiang/Shantung/Hopei/Honan/Kwangtung/Szechuan/Sinkiang/… +
  大小写变体 jilin/Jilin + 个别中文)——建**省 → 别名清单**静态映射(覆盖全部 64 实测变体),
  谓词 = state COLLATE NOCASE IN(别名) ∪ 台名 LIKE 省名 ∪ tag LIKE 省名(沿用三路并集)。
- 其他国家 → **静态分类网格**(11 张卡,仅显示台数 > 0 的,固定枚举序):

```kotlin
data class RadioCategory(
    val id: String, val displayName: String,
    val matchTags: List<String>,        // tags 字段逗号分隔精确匹配(含中文显示名对应的英文 tag)
    val nameKeywords: List<String> = emptyList()  // 台名关键词(覆盖海外中文台)
)
val CATEGORIES = listOf(   // 用户枚举的 10 类 + 交通;v6 初稿的"音乐"被用户清单移除
    cat("pop",      "流行",   tags=["pop","top 40"]),
    cat("rock",     "摇滚",   tags=["rock","pop rock"]),
    cat("news",     "新闻",   tags=["news","information"], kw=["新闻"]),
    cat("classical","古典",   tags=["classical"]),
    cat("jazz",     "爵士",   tags=["jazz","smooth jazz"]),
    cat("oldies",   "老歌",   tags=["oldies","70s","80s","90s"]),
    cat("dance",    "舞曲",   tags=["dance","electronic","house"]),
    cat("finance",  "财经",   tags=["business","economics","finance"], kw=["财经","经济"]),
    cat("talk",     "谈话",   tags=["talk"]),
    cat("culture",  "文艺",   tags=["culture"], kw=["文艺","戏曲","评书"]),
    cat("traffic",  "交通",   tags=["traffic"], kw=["交通"])
)
```

**第三级 = 台列表**:该国家×分类 / 中国×省份 的 top 100,ORDER BY clickcount DESC(平票 votes DESC)。
分类 tab 内三层栈,BackHandler 逐层回退。

- tag 匹配必须**分隔符精确**:(','||tags||',') LIKE '%,pop,%'——杜绝 "pop" 误命中 "synthpop"/"pop rock"(各自有独立分类);SQLite LIKE 对 ASCII 天然大小写不敏感。
- 可见性过滤复用三态(health/deleted/localDeadUntil);码率过滤沿用。
- 本省 tab 与「中国→省份」**共用同一套别名谓词**(同一省两边结果一致;本省命中面同时变宽)。

### D-B · 排序 = clickcount(近期实际收听)

- Schema v5→v6:`radio_stations` ADD `votes INTEGER NOT NULL DEFAULT 0`、`clickcount INTEGER NOT NULL DEFAULT 0`(实体 @ColumnInfo(defaultValue),**迁移测试先于实现**,MigrationTest 扩展 5→6)。
- 同步 ApiStation 增映射 votes/clickcount;seed 生成脚本增两字段。
- 分类列表:ORDER BY clickcount DESC LIMIT 100(v5-C 决策:分类=精选入口,长尾靠搜索)。
- 失效台处置:反应式(播放失败→localDeadUntil→浏览列表自动隐藏),clickcount 排序天然把失效台沉底。
- **D-B2(实施期增补)发版侧 CN 可达性探测**:clickcount 统计的是点进尝试,失效台(点进率反而高)不会自动沉底——实测红线首跑 11 分类×top5 仅 83%(死台按播出方集群集中在 top:SWR3 一台占 3 分类、France Médias 死簇)。增补:seed 构建时对 11 分类×clickcount top15 做连接级拉流探测(10s×2 次失败记死;SSL 链问题视为活——服务器应答,判定归播放期),死台在 seed 内标记 health=0,浏览列表即刻隐藏。复活周期=运行时 doSync(仅 CN+topvote1000,7 天)或下周 seed 重探;已挂账设备的 localDeadUntil 同步禁触,始终兜底。探测结果存 .corpus-raw/probe_results.json 供审计。

### D-C · UI = 分类卡网格替代推荐位(PlaylistPanel 同款视觉)

- 电台页 Tab 结构:**分类(默认)/ 收藏 / 本省 / 搜索**。
- 分类页三层:国家网格(LazyVerticalGrid Adaptive 150dp,与推荐歌单同款;卡 = 国旗 emoji + 中文名 + 台数)
  → 二级网格(中国=省份卡 / 他国=分类卡)→ 台列表 top 100(StationRow 沿用);BackHandler 逐层回退,VM 持栈切 tab 不丢。
- 原"全球热门"推荐位退役(其 votes 口径 28% 失效);loadHot/hot 查询删除。
- 卡片台数:国家分组 1 个 GROUP BY 扫描;省份/分类各为单国 COUNT(走 countryCode 索引),VM 缓存。

### D-D · 数据新鲜度分层(如实的取舍)

| 语料段 | 刷新通道 | 频率 |
|---|---|---|
| CN 全量 + top1000 | 运行时同步(既有管道) | 每 7 天 |
| 全球长尾(~5.2 万台) | **随发版的 seed 重生成**(并行拉取 7.5 分钟) | 每周发版时 |
| long-tail 的 health 新鲜度 | 随发版刷新;其间失效 = 播放失败挂账自动隐藏 | — |

运行时拉全量 = 1 小时车机流量,不可接受;周更流程本来就要重新生成 seed,零增量成本。

## 四、验证

- 单测:分类匹配的**分隔符边界**("pop" 不命中 "synthpop"/"pop rock",各自独立分类)、台名关键词命中(海外中文台)、可见性三态过滤、clickcount 排序 + LIMIT 100、国家分组(中国钉首位 + clicks 降序)、**省别名谓词**(state='Kiangsu' 命中"江苏"、'jilin' 命中"吉林")、迁移 5→6(MigrationTest 扩展)。
- 实测(发版红线):12 个分类各抽 top 5 台拉流,汇总可播率;目标 **≥ 85%**(对照 votes 推荐位的 72%)。
- 车机冒烟:分类卡显示台数 → 进分类 → 播放 → 收藏 → 徽标重试。

## 五、工作量

| 项 | 规模 |
|---|---|
| 数据层(迁移 5→6 + 分类查询 + 同步字段) | ~250 行 |
| UI(分类网格 + 二级列表页) | ~300 行 |
| 单测 + 迁移测试扩展 | ~200 行 |
| seed 重生成脚本(votes/clickcount) | ~40 行 |
| 合计 | ~800 行 |
