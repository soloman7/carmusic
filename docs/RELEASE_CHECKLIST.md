# 每周发版门禁清单

> 每一条都必须留下验证证据(命令 + 结果摘要)。**任何一项不过,不发版。**

## 自动红线(全部通过才继续)

- [ ] `gradlew.bat assembleDebug` 构建通过
- [ ] `gradlew.bat testDebugUnitTest` 全部通过(默认模式,真实 API 测试跳过)
- [ ] `gradlew.bat testDebugUnitTest -PintegrationTests` 真实网络测试通过(4 条)
- [ ] `python scripts/test_playlist_sources.py` 退出码 0(任何 ❌ 即拦截;⚠️ warn 项需在 README 标注)
- [ ] `python scripts/test_netease_weapi.py` 通过

## 应用内更新端到端(升版本号后必做)

- [ ] 上一版(车机当前版本)启动 → 设置页检测到新版
- [ ] 下载完成,进度正常,SHA-256 校验通过
- [ ] 点"立即安装"拉起系统安装器(FileProvider 链路)
- [ ] 安装成功,旧数据(收藏/历史/队列)保留

## 车机冒烟(README 功能验证清单 10 项)

- [ ] 搜索"周杰伦"返回多平台结果
- [ ] 点击歌曲播放,进度条正常
- [ ] 歌词同步滚动,点行 seek
- [ ] 熄屏音乐继续,通知栏有控制卡片
- [ ] 方向盘按键能切歌
- [ ] 打开导航音乐音量自动降低
- [ ] 收藏一首歌,重启 APP 仍在
- [ ] 横屏封面/歌词分栏
- [ ] 起步后自动进入驾驶模式,大按钮可用
- [ ] 均衡器调节实时生效

## 发版产物

- [ ] `versionCode` +1,`versionName` 更新(app/build.gradle.kts)
- [ ] README 顶部版本号与特性段落更新
- [ ] `gradlew.bat assembleRelease` 产出 `app/build/outputs/apk/release/app-release.apk`
- [ ] 重命名为 `carmusic-vX.Y.Z-release.apk` 放仓库根目录
- [ ] SHA-256 写入自托管 version.json(sha256 字段)
- [ ] git 提交(格式:`vX.Y.Z: 变更摘要`)并 push
- [ ] GitHub Release 打 tag `vX.Y.Z` 并附 APK

## 执行记录

| 日期 | 版本 | 各项结果 | 执行人 |
|---|---|---|---|
| 2026-09-06 | v3.4.0 | assembleDebug ✅ / 单测 62 条 0 失败 ✅ / integrationTests 全过(网易 weapi 曲目测试 JVM 下按前提跳过,由 test_netease_weapi.py ✅ 覆盖) / 回归脚本 7/7 exit 0 ✅ / test_netease_weapi.py ✅ / 应用内更新端到端待车机实测(本版首次修复 FileProvider,装上 v3.4.0 后下一版必须走通) / 冒烟 10 项待车机 | ZCode |
| 2026-09-07 | v3.4.2 (dry-run) | 车机实测反馈：v3.4.0 检查更新✅、下载✅(走 gh-proxy 加速通道)、点安装被 DiLink 拒绝("多媒体系统不支持该操作")→ 定位为 ACTION_MANAGE_UNKNOWN_APP_SOURCES 授权页与 ACTION_INSTALL_PACKAGE 均被 DiLink 路由拒绝。v3.4.2(code 23) 改 ACTION_VIEW 优先(文件管理器同款路径)并去掉授权页跳转。待 U盘安装 v3.4.2 后再干跑验证安装链路 |
| 2026-09-07 | v3.4.2 干跑验证 ✅ **更新链路闭环** | 车机 U盘装入 v3.4.2(code 23) 后干跑(24/3.4.3 同包重装)：检查更新✅(update_url=ghproxy.net 包裹 raw,gh-proxy.com 与 raw 直连先后失效)、下载✅(ghproxy.net→dl 分支 7.5MB)、SHA256 校验✅、**立即安装✅(ACTION_VIEW 弹出系统安装器)**、安装完成数据保留。结论:DiLink 应用内更新全链路可用;每周发版=更新 dl 分支 APK+version.json 四字段。注意:raw/github.com release/gh-proxy 均间歇不可用,ghproxy.net 当前可用,治本方案=迁 Gitee(待用户注册) | ZCode |
| | | | |
