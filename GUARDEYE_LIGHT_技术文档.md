# GuardEye Light 技术文档

> 版本：2.3.6 | 更新日期：2026-06-15 | 平台：Android（minSdk 26 / targetSdk 34）

---

## 一、项目概述

GuardEye Light 是 GuardEye 系列 Android 应用的轻量版，专注于**远程拍照监控**和**澳门交通告票查询**两个核心功能，通过 Telegram Bot 实现远程控制。

| 项目 | 说明 |
|---|---|
| 包名 | `com.guardeye` |
| 构建工具 | Gradle + Kotlin DSL |
| 语言 | Kotlin |
| 最小系统 | Android 8.0（API 26）|
| 目标系统 | Android 14（API 34）|
| 当前版本 | 2.3.6（versionCode 6）|

---

## 二、技术架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────┐
│           Telegram Bot（远程控制）          │
│         /start /stop /photo /ticket        │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│         LightBotService（前台服务）         │
│  ┌──────────┐  ┌─────────────────────┐ │
│  │ CameraX   │  │ TicketChecker（告票）│ │
│  │ 拍照引擎  │  │ OkHttp + FSM 网站    │ │
│  └──────────┘  └─────────────────────┘ │
│  ┌──────────────────────────────────────┐  │
│  │  TelegramBot.kt（API 封装）       │  │
│  └──────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  LightMainActivity / LightSettingsActivity   │
│            （UI 控制层）                    │
└─────────────────────────────────────────────┘
```

### 2.2 关键依赖

| 依赖 | 用途 |
|---|---|
| `androidx.camera:camera-camera2` | CameraX 拍照引擎 |
| `com.squareup.okhttp3:okhttp` | HTTP 客户端（Telegram + FSM）|
| `org.json:json` | JSON 解析 |
| `androidx.lifecycle:lifecycle-service` | Service 生命周期管理 |
| `kotlinx-coroutines` | 异步任务调度 |

---

## 三、已开发的关键技术

### 3.1 CameraX 拍照引擎

**文件**：`LightBotService.kt`

**实现要点**：

- **单次绑定复用**：`ImageCapture` 在 Service 启动时绑定一次，后续所有拍照操作复用同一实例，避免频繁 bind/unbind 导致的相机冲突
- **后台不暂停**：自定义 `CameraLifecycleOwner`，状态固定为 `STARTED`，即使 App 进入后台或锁屏，相机回调仍正常触发
- **前镜头切换**：拍照前 unbind 并重新 bind 前镜头，拍完自动恢复后镜头
- **前镜头预览**：`/preview` 指令触发，每 2 秒拍一帧，持续 30 秒，自动推送到 TG

**代码关键段**：
```kotlin
// CameraLifecycleOwner — 后台不暂停的关键
private class CameraLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    init { lifecycleRegistry.currentState = Lifecycle.State.STARTED }
    override val lifecycle: Lifecycle get() = lifecycleRegistry
}
```

**已知问题**：`/photo f` 前镜头拍照偶尔出现「相机正忙」错误（前镜头恢复后镜头未完全就绪），已加 500ms 延迟兜底。

---

### 3.2 后台保活机制

**文件**：`LightBotService.kt`

**五层保活策略**：

| 层级 | 技术 | 作用 |
|---|---|---|
| 1 | `ForegroundService` | 系统不会优先杀死前台服务 |
| 2 | `PARTIAL_WAKE_LOCK` | 屏幕关闭后 CPU 仍保持运行 |
| 3 | WakeLock Keep-Alive | 每 8 分钟重新获取 WakeLock，防止内核超时释放 |
| 4 | `AlarmManager.setAlarmClock()` | 最高优先级闹钟，Doze 模式下必触发 |
| 5 | `NotificationManager.IMPORTANCE_HIGH` | 前台通知设为高重要性，降低被系统查杀概率 |

**电池优化白名单**：通过 `/battery` 指令引导用户将 App 加入电池优化白名单（Doze 豁免）。

---

### 3.3 Telegram Bot 远程控制

**文件**：`TelegramBot.kt`、`LightBotService.kt`

**已实现指令**：

| 指令 | 功能 |
|---|---|
| `/start` | 启动监控（拍照 + 告票）|
| `/stop` | 停止监控 |
| `/photo` | 立即拍照（默认后镜头，加 `f` 前镜头）|
| `/photo h/m/l/x` | 指定画质（1920×1080 / 1280×720 / 854×480 / 原图）|
| `/ticket` | 手动触发告票查询 |
| `/preview` | 前镜头预览（30 秒）|
| `/interval N` | 设置拍照间隔（1-60 分钟）|
| `/status` | 查看当前状态 |
| `/test` | 测试 Bot 是否在线 |
| `/battery` | 打开电池优化设置 |
| `/debug` | 开启/关闭调试模式 |

**长轮询机制**：`fetchUpdates()` 使用 `timeout=30s` 的长轮询，有新指令时 500ms 内响应，无指令时等待 30 秒。网络错误自动指数退避（10s → 60s 上限）。

---

### 3.4 澳门交通告票查询

**文件**：`TicketChecker.kt`

**技术实现**：

- **FSM 网站爬取**：模拟 ASP.NET WebForms 表单提交（ViewState + EventValidation）
- **Session 管理**：每个车牌独立 GET → POST，手动管理 CookieJar
- **302 重定向处理**：OkHttp 禁止自动重定向，手动处理 302 跳转
- **结果解析**：三层检查（对齐 Node.js `traffic_ticket_query.js`）
  1. `lbMsgText`（系统错误消息）
  2. `lbNoTicket2`（「沒有違例紀錄」在此）
  3. `html.contains()` 兜底

**TG 输出格式**：
```
2026-06-15 02:27:30
MO9560，沒有違例紀錄
AA5186，有違例紀錄
```

---

### 3.5 照片后处理

**文件**：`LightBotService.kt` → `resizeJpeg()`

**功能**：

- **尺寸缩放**：根据画质参数（H/M/L/X）缩放到目标分辨率
- **旋转校正**：横拍照片自动旋转 270°（常量 `ROTATE_LANDSCAPE`）
- **中心裁剪**：只裁上下，不裁左右，保持 16:9 比例
- **JPEG 压缩**：H=95%、M=70%、L=50%、X=95%（仅去除 EXIF）

---

### 3.6 配置管理

**文件**：`Config.kt`

**存储方式**：`SharedPreferences`（`guard_eye_prefs.xml`）

**关键配置项**：

| Key | 说明 |
|---|---|
| `bot_token` / `chat_id` | Telegram Bot 凭证 |
| `interval_minutes` | 拍照间隔（1-60 分钟）|
| `enabled` | 主监控开关 |
| `ticket_enabled` | 告票监控开关 |
| `ticket_interval_minutes` | 告票查询间隔（5-60 分钟）|
| `ticket_plates` | 车牌列表（空格/逗号分隔）|
| `ticket_last_result` | 上次查询结果（JSON，用于对比）|
| `debug_enabled` | 调试模式开关 |
| `last_capture_path` | 最近一张照片的路径 |

**注意**：`lastIntervalCapture` 和 `lastIntervalCaptureTime` 两个 Key 重复定义了（都是 `KEY_LAST_INTERVAL_CAPTURE`），这是一个 Bug，会导致读取异常。

---

## 四、待优化的点

### 4.1 高优先级

#### P0 - 相机冲突问题
- **现象**：`/photo f` 前镜头拍照偶尔报错「相机正忙」
- **原因**：前镜头拍照后恢复后镜头时，`bindImageCapture()` 异步执行，500ms 内可能未完成
- **修复方向**：在 `bindImageCapture()` 完成时加回调通知，而非固定延迟

#### P0 - Config.kt 重复 Key 定义
- **现象**：`lastIntervalCapture` 和 `lastIntervalCaptureTime` 都指向同一个 Key
- **影响**：间隔拍照时间记录读取错误
- **修复方向**：删除重复的 `lastIntervalCaptureTime`，统一使用 `lastIntervalCapture`

#### P1 - 告票查询正则跨行匹配
- **现象**：`RE_PLATE` 和 `RE_CAR_LABEL` 仍使用 `[^<]*`（不支持跨行）
- **影响**：如果 FSM 返回的 HTML 中这些元素内容跨行，会导致解析失败
- **修复方向**：将这两个正则也改为 `[\\s\\S]*?`

---

### 4.2 中优先级

#### P2 - 电池温度读取频率过高
- **现象**：每次拍照都读取电池温度（`getBatteryTemperature()`）
- **影响**：频繁调用 `registerReceiver()` 可能带来性能开销
- **优化方向**：改为每分钟更新一次，或仅在拍照后更新

#### P2 - Telegram 长轮询阻塞
- **现象**：`fetchUpdates()` 使用 30 秒超时，网络切换时可能卡住
- **影响**：网络从 WiFi 切换到移动数据时，轮询可能中断
- **优化方向**：使用 `OkHttp` 的超时机制，或在网络变化时主动取消当前请求

#### P2 - 照片存储无清理机制
- **现象**：`last_capture.jpg` 每次拍照都覆盖，但历史照片未清理
- **影响**：如果用户手动拍照多次，旧照片仍占用存储空间
- **优化方向**：添加照片清理策略（保留最近 N 张，或按时间自动清理）

---

### 4.3 低优先级（功能增强）

#### P3 - FCM 推送替代轮询
- **现状**：使用长轮询（Long Polling）接收 TG 指令
- **优势**：实现简单，不需要额外配置
- **劣势**：耗电较高，实时性受轮询间隔影响
- **优化方向**：接入 Telegram Bot API 的 Webhook 模式，或通过 FCM 推送唤醒 App

#### P3 - 告票查询增量推送
- **现状**：每次查询都推送完整结果
- **优化方向**：仅在有状态变化（无违章 → 有违章）时推送通知，减少不必要的信息干扰

#### P3 - 相机参数可配置
- **现状**：画质、旋转角度等参数硬编码
- **优化方向**：在设置页开放相机参数配置（画质、旋转角度、闪光灯模式等）

#### P3 - 多语言支持
- **现状**：界面和 TG 消息均为中文
- **优化方向**：添加英文/葡文支持（澳门用户可能需要）

---

## 五、版本历史

| 版本 | 日期 | 关键变更 |
|---|---|---|
| 2.3.6 | 2026-06-15 | 修复 `hasTicket` 判断逻辑，TG 输出恢复文字格式 |
| 2.3.5 | 2026-06-14 | 修复 `parseResponse()` 正则跨行匹配问题 |
| 2.3.4 | 2026-06-14 | UI 调整（移除 badgeTicket、Tab 改名、token 框默认隐藏）|
| 2.3.3 | 2026-06-14 | TG 输出格式改为极简图标（后恢复为文字）|
| 2.3.2 | 2026-06-14 | 修复告票查询核心 Bug（Session、POST body、正则匹配）|
| 2.2.1 | 2026-06-13 | 修复 WakeLock 释放时机、Bitmap 解码移至后台线程 |

---

## 六、构建与部署

### 6.1 构建命令

```bash
# 清理并编译 Light 变体 Debug APK
./gradlew.bat clean assembleLightDebug

# APK 输出路径
app/build/outputs/apk/light/debug/app-light-debug.apk
```

### 6.2 签名配置

- **Debug 签名**：使用 `guard-eye.jks`（密码：`GuardEye2026`）
- **Release 签名**：未启用 ProGuard（`isMinifyEnabled = false`）

### 6.3 Git 管理

- **仓库**：`https://github.com/johnhuang888/guardeye.git`
- **分支**：`main`
- **未推送 Commit**：5 个（截至 2026-06-15）
- **未推送 Tag**：`v2.3.2`、`v2.3.3`、`v2.3.4`
- **注意**：GitHub PAT 已失效，需要重新生成并更新 Windows Credential Manager

---

## 七、测试建议

### 7.1 功能测试

- [ ] 拍照功能（后镜头、前镜头、不同画质）
- [ ] TG 指令集（所有指令）|
- [ ] 告票查询（有违章、无违章、车牌错误）
- [ ] 后台保活（锁屏、退出 App、重启手机）
- [ ] 电池优化白名单（加入后是否更稳定）

### 7.2 兼容性测试

- [ ] Android 8.0（minSdk 26）
- [ ] Android 10（分区存储）
- [ ] Android 12+（前台服务权限）
- [ ] Android 14（targetSdk 34）

---

## 八、关键技术决策记录

### 8.1 为什么用 CameraX 而不是 Camera2？

- CameraX 提供更简单的 API，生命周期自动管理
- 向后兼容到 Android 5.0（本项目 minSdk 26，完全支持）
- CameraX 的 `ImageCapture` 支持 JPEG 质量参数，满足画质调节需求

### 8.2 为什么用长轮询而不是 Webhook？

- Webhook 需要公网 IP 或内网穿透，部署复杂
- 长轮询实现简单，适合个人项目
- Telegram 的 `getUpdates` 长轮询稳定性高，实际使用中未出现明显延迟

### 8.3 为什么告票查询不用 API 而是爬网页？

- 澳门交通事务局（FSM）未提供开放 API
- 只能通过网页表单查询，必须模拟浏览器行为
- 已对齐 Node.js 脚本 `traffic_ticket_query.js`，确保逻辑一致

---

*文档生成时间：2026-06-15 02:40*
*生成工具：WorkBuddy + GuardEye Light 源码扫描*
