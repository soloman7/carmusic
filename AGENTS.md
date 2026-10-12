# carmusic — Agent 指南

<!-- PMH:START -->
## 项目记忆指针（project-memory-hub 自动维护，勿改标记块）

本项目由统一记忆库托管。开始工作前先读这两份文件恢复上下文：
- 档案（目标/状态/关键决策/待办）: `D:/Obsidian/projects/carmusic/PROJECT.md`
- 活动日志（各 harness 会话流水，只追加）: `D:/Obsidian/projects/carmusic/log.md`

取得实质进展或做出决策时：
1. 往 log.md 追加一行：`- [YYYY-MM-DD HH:MM] [harness名] 进展/决策内容`
2. 若项目状态、待办有变化，同步更新 PROJECT.md 对应小节（保持五段式结构）

### 当前状态快照（每日聚合任务刷新，可能滞后）
当前版本以 `app/build.gradle.kts` 的 versionName/versionCode 为唯一版本源（v3.9.0+ 整改）。本轮修复：①猫耳点播无声（流请求缺 Referer/UA，PlaybackHttpHeaders + MediaSource.headers）②推荐歌单滑到底闪退（Lazy key 重复 + 封面原图 OOM，distinctBy + index key + Coil 降采样）③更新缺 sha256 拒绝 ④SMTP 邮件导出改为系统分享。单测含新增 PlaybackHttpHeaders/UpdateManager 合规用例。待 M2（方向盘模式仲裁/DriveMode 电台按钮/收藏排序）、M3（byuuid 复验 worker/Auto 电台节点）。
<!-- PMH:END -->
