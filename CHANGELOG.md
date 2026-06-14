# Changelog

## [2.3.3] - 2026-06-14

### Changed
- **TicketChecker.kt**：`pushToTelegram()` 输出格式改为极简图标方案 C
  - 🟢 = 无违章（沒有違例紀錄）
  - 🔴 = 有违章（有違例紀錄）
  - 格式：`车牌 🟢🔴`（上次图标 + 本次图标）
  - 首次查询（无上次记录）：只显示本次图标
  - 查询失败/查無資料：保持原文字输出

---

## [2.3.2] - 2026-06-14

### Fixed
- **TicketChecker.kt**：修复告票查询返回「查無資料」的核心 bug
  - 修复 `RE_VIEWSTATE`/`RE_EVENTVALIDATION` 正则匹配 `id=`，改为匹配 `name=`（FSM 网页隐藏字段使用 `name=` 属性）
  - 修复 POST body 拼接：`__EVENTTARGET`/`__EVENTARGUMENT` 不带 `=` 号（对齐 Node.js `traffic_ticket_query.js`）
  - 修复 Session 不一致问题：每个车牌独立发起 GET + POST，CookieJar 手动管理
  - 修复 OkHttp 自动重定向导致 Cookie 丢失：`followRedirects(false)`，手动处理 302
  - 修复按钮文字 `BTN_OK`：从 `"確  定"`（三空格）改为 `"確定"`（无空格，与 FSM 网页一致）
  - 修复 `parseResponse()` 缺少 `lbNoTicket2` 判断，导致「沒有違例紀錄」被误判为「查無資料」
  - 添加 `Log.d()` 日志，方便在 Logcat 中调试 HTTP 请求

### Changed
- **TicketChecker.kt**：`queryPlate()` 完全重写，对齐系统 `traffic_ticket_query.js` 逻辑
- **TicketChecker.kt**：`parseResponse()` 改为三层检查（对齐 `parseResult()`）
  1. `lbMsgText`（系统消息）
  2. `lbNoTicket2`（「沒有違例紀錄」在此）
  3. `indexOf` 兜底（直接搜索 HTML）

### Referenced
- 对比分析系统 `traffic_ticket_query.js`（Node.js）与 `TicketChecker.kt`（Kotlin）
- 本地 Node.js 测试脚本验证通过：`MO9560` 正确返回「沒有違例紀錄」

---

## [2.2.1] - 2026-06-13

### Fixed
- **LightBotService.kt**：修复 `startKeepAlive()` 错误调用 `stopKeepAlive()` 导致 WakeLock 提前释放（P0）
- **LightMainActivity.kt**：`loadLastCaptureImage()` Bitmap 解码移至后台线程（P0）
- **Config.kt**：`setter` 从 `apply()` 改为 `commit()`，确保立即写入
- **GuardEyeApplication.kt**：`NotificationChannel` 创建移至 `Application.onCreate()`，避免重复创建

### Added
- **GuardEyeApplication.kt**：新增 Application 类，统一管理 NotificationChannel
- **AndroidManifest.xml**：注册 `GuardEyeApplication`

---
