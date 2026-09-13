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
当前版本 v3.4.2（versionCode 23），项目处于活跃迭代后期、功能相当完整。2026-09-07 整天在做应用内自动更新链路的车机实测与修复：APK 下载代理从 gh-proxy.com 切换到 ghproxy.net、DiLink 安装 intent 兼容（ACTION_VIEW 优先）、干跑验证通过后 version.json 恢复正式态——更新链路已闭环（依据 git log）。62 条单测 + 音源有效性回归脚本作为发版红线。
<!-- PMH:END -->
