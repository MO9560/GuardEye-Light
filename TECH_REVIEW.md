# GuardEye Android 项目技术评审文档

> 生成时间：2026-06-20
> 版本：GuardEye Light（v4.x 轻量版）

---

## 一、项目架构总览

GuardEye 是一个 Android Telegram Bot 监控应用，两个构建变体共享同一套 `main/` 源码：

| 目录 | 变体 | 说明 |
|---|---|---|
| `src/main/` | 共享 | `Config`、`TelegramBot`（HTTP 工具）、`GuardEyeApplication` |
| `src/light/` | **Light** | CameraX 定时拍照，无 AI 检测，专注低功耗 |
| `src/full/` | Full | CameraX AI 持续监控 + YOLOv8n 人车检测 + 录像 |

本文档聚焦 **Light 变体**，因为这是当前活跃开发的分支。

---

## 二、核心模块依赖关系

```
LightMainActivity
    ├── LightSettingsActivity (设置页)
    ├── LightBotService (核心服务)
    │     ├── TelegramBot (HTTP 工具类，OkHttp 封装)
    │     ├── Config (SharedPreferences 封装)
    │     ├── ServiceStatus (进程内状态收集)
    │     ├── TicketChecker (澳门告票查询)
    │     └── CameraX (ImageCapture)
    │           └── CameraLifecycleOwner (独立 LifecycleOwner)
    ├── LightAlarmReceiver (主监控定时器 AlarmManager)
    ├── LightAlarmReceiverTicket (告票定时器)
    ├── LightBootReceiver (开机自启)
    └── LightBotService.ACTION_* intents
```

---

## 三、关键技术方案

### 3.1 Telegram Bot 通信（OkHttp + 轮询）

**文件：** `src/main/java/com/guardeye/TelegramBot.kt`

```
OkHttpClient（全局单例）
    ├── sendText()     → POST /bot{token}/sendMessage   (JSON)
    ├── sendPhoto()    → POST /bot{token}/sendPhoto      (multipart/form-data)
    └── fetchUpdates() → POST /bot{token}/getUpdates     (JSON，long polling 30s timeout)
```

**轮询循环在 `LightBotService.startPolling()` 中：**
```kotlin
// 伪代码
while (true) {
    val updates = TelegramBot.fetchUpdates(token, Config.botOffset)
    for (update in updates) {
        Config.botOffset = update.updateId + 1  // 持久化 offset
        handleCommand(update.text, update.chatId)
    }
    delay(if (updates.isEmpty()) 1500ms else 500ms)
}
```

**已知风险：`Config.botOffset` 是 Long 类型，用 `.commit()` 同步写 SharedPreferences。**  
在高频率写入场景（大量消息）下，UI 线程可能被阻塞，且 `.commit()` 返回 false 时不会报错，offset 可能丢失。

### 3.2 后台保活：五层策略

**文件：** `LightBotService.kt`

| 层次 | 机制 | 说明 |
|---|---|---|
| 1 | `START_STICKY` | 系统杀后台后尝试重启 Service |
| 2 | `PARTIAL_WAKE_LOCK` | CPU 持续运行，屏幕可关闭 |
| 3 | WakeLock 续期（8min 一次） | 防止 10min 内核超时释放 |
| 4 | `AlarmManager.setAlarmClock()` | 最高优先级闹钟，Doze 下仍触发 |
| 5 | `PRIORITY_HIGH` 前台通知 | 降低系统 kill 优先级 |

**`setAlarmClock` vs `setExactAndAllowWhileIdle`：**
`setAlarmClock` 会显示精确闹钟图标（用户可见），但这是唯一能在 Deep Doze 模式下保证触发的 API。

### 3.3 CameraX 生命周期管理

**痛点：** Android 设备进入后台时，标准 `LifecycleService` 会降为 CREATED 状态，导致 CameraX 预览停止。

**GuardEye 的解决方案：自定义 `CameraLifecycleOwner`**

```kotlin
// 一个独立于 app lifecycle 的 LifecycleOwner，永远保持 STARTED
private class CameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    init { registry.currentState = STARTED }
    fun start() { registry.currentState = STARTED }
    fun stop() { registry.currentState = CREATED }
}
```

`ImageCapture` 绑定到此独立的 `CameraLifecycleOwner`，不受 app 切后台影响。

### 3.4 照片质量分级

```kotlin
// 4 档质量，JPEG 压缩率不同，输出分辨率不同
PhotoQuality.HIGH   → 1920×1080, JPEG 95%
PhotoQuality.MEDIUM → 1280×720,  JPEG 70%
PhotoQuality.LOW    → 854×480,   JPEG 50%
PhotoQuality.RAW    → 原分辨率,   JPEG 95%（只去 EXIF，不缩放）
```

**`resizeJpeg()` 流程：**
1. `inJustDecodeBounds` 解码尺寸（省内存）
2. 计算 `inSampleSize` 降采样
3. 硬编码旋转角度（横拍 270°，竖拍 0°）
4. 居中裁切 + 等比缩放到目标分辨率
5. 重新编码为 JPEG（剥离 EXIF，定向信息 bake 入像素）

**旋转角度硬编码为 `270f`**，注释说明可切换 `90f`，需注意不同设备方向传感器可能不同。

### 3.5 前镜头切换

```kotlin
// 每次前镜头操作：
provider.unbindAll()
provider.bindToLifecycle(lifecycle, DEFAULT_FRONT_CAMERA, imgCapture)
// ... capture ...
// 500ms 后重新 bind 回后镜头
mainHandler.postDelayed({ bindImageCapture() }, 500)
```

**注意：** `provider.unbindAll()` 会断开所有已绑定的用例，如果此时后镜头正在被用于预览，会中断。

### 3.6 澳门告票查询（TicketChecker）

**目标 URL：** `https://www.fsm.gov.mo/webticket/Webform1.aspx?carClass=L&Lang=C`

**完整流程：**
```
Step 1: GET 页面 → 提取 __VIEWSTATE + __EVENTVALIDATION + Cookie
Step 2: POST 表单（车牌 + 隐藏字段 + 确定按钮）
Step 3: 处理 302 重定向 → 获取结果页
Step 4: 正则解析 lbMsgText / lbNoTicket2 / lbGetNum 等元素
Step 5: Telegram 推送结果（文字，无图片）
```

**车牌格式验证：** `[A-Z]{2}[0-9]{4}`（如 `MX-1234` → `MX1234` 大写后验证）

**告票状态检测：**
- 有违例：`msgText` 非空 或 HTML 包含"有違例紀錄"
- 无违例：`lbNoTicket2` 含"沒有違例紀錄" 或 HTML 含"沒有違例紀錄"
- 查无资料：以上皆不匹配

---

## 四、配置持久化（SharedPreferences）

**文件：** `src/main/java/com/guardeye/Config.kt`

| Key | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `bot_token` | String | `""` | Telegram Bot Token |
| `chat_id` | String | `""` | 目标 Chat ID（DM 或群组） |
| `interval_minutes` | Int | `5` | 定时拍照间隔（1-60） |
| `enabled` | Boolean | `false` | 主监控开关 |
| `debug_enabled` | Boolean | `true` | 调试日志开关 |
| `bot_offset` | Long | `0L` | Telegram getUpdates offset |
| `ticket_enabled` | Boolean | `false` | 告票监控开关 |
| `ticket_interval_minutes` | Int | `10` | 告票查询间隔（5-60） |
| `ticket_plates` | String | `""` | 车牌列表（逗号/空格分隔） |

**写入方式：`apply()` vs `commit()`**
- `apply()`：异步写入，无返回值，不阻塞
- `commit()`：**同步写入**，返回成功/失败，会阻塞 UI 线程

当前代码中 `botToken`、`chatId`、`botOffset` 用 `.commit()`，其余用 `.apply()`。  
**建议：`botOffset` 也改为 `.apply()`，避免在 IO 线程中阻塞。**

---

## 五、数据流图

### 定时拍照路径
```
AlarmManager.setAlarmClock (每 N 分钟)
  → LightAlarmReceiver.onReceive()
    → startForegroundService(ACTION_CAPTURE)
      → LightBotService.onStartCommand(ACTION_CAPTURE)
        → captureAndSend("interval")
          → ImageCapture.takePicture() [CameraX]
            → resizeJpeg()
              → TelegramBot.sendPhoto()
                → Telegram Server → 用户设备
```

### Telegram 命令路径（远控）
```
用户发送 /photo 或 /status
  → Telegram Server 持有消息
    → LightBotService 轮询 getUpdates()
      → TelegramBot.fetchUpdates(token, offset)
        → 更新 Config.botOffset
          → handleCommand(text, chatId)
            → 拍照 or 回复文字
```

### 告票查询路径
```
AlarmManager.setAlarmClock (每 M 分钟)
  → LightAlarmReceiverTicket.onReceive()
    → startForegroundService(ACTION_CHECK_TICKET)
      → LightBotService.onStartCommand(ACTION_CHECK_TICKET)
        → TicketChecker.checkAndPush() [协程 IO]
          → GET/POST FSM 网站
            → TelegramBot.sendText()
              → 推送结果到用户
```

---

## 六、已知风险与建议

### 🔴 高风险

**1. `botOffset` 同步写入 UI 线程**
- 位置：`Config.kt` - `set(v) { ... .commit() }`
- 影响：消息量多时可能阻塞主线程，或因写入失败导致 offset 丢失 → 消息重复/遗漏
- 建议：改为 `apply()`，并添加 offset 异常时的重置逻辑（当 `fetchUpdates` 返回空但 offset 已很高时，清零）

**2. 前镜头 bind 时 `unbindAll()` 会中断后镜头**
- 位置：`captureFrontCamera()`、`captureFrontCameraSilent()`
- 影响：前镜头操作期间如果后台有定时拍照触发，后镜头会被强制 unbind
- 建议：使用 `ImageAnalysis` 用例替代临时 bind/unbind，或在前镜头操作期间忽略定时器触发

**3. 群组 chat ID 类型不明确**
- `Config.chatId` 是 String，但 Telegram 群组 ID 通常是负数或 `-100xxxxxxxxxx`
- `TelegramBot.sendText()` 直接将 String 作为 `chat_id` 发送，需要确认 Telegram API 对负数 chat_id 的处理是否有一致性
- 建议：使用 `toLong()` 或保留 String，但需测试负数群 ID 场景

### 🟡 中风险

**4. 旋转角度硬编码**
- 位置：`private const val ROTATE_LANDSCAPE = 270f`
- 不同设备可能返回不同 EXIF 方向值，270° 不一定适用于所有机型
- 建议：读取 JPEG EXIF Orientation tag 动态判断

**5. 照片尺寸正则提取备用方案脆弱**
- 位置：`TicketChecker.extractHiddenFields()` 有两套正则，降级逻辑不够健壮
- FSM 网站改版可能破坏车牌查询功能
- 建议：添加版本检测（HTML 中特征字符串），失效时主动提示用户更新

**6. `ServiceStatus` 单例跨进程不共享**
- `ServiceStatus` 是进程内单例，如果 App 被系统重启（如低内存），状态丢失
- 状态 Activity 读取到的可能不是真实状态
- 建议：`StatusActivity` 展示数据时添加"数据可能已过期"提示，或从 `SharedPreferences` 读取上次状态

### 🟢 低风险 / 建议

**7. 图片预览帧率 0.5fps（2s/帧）**
- 位置：`startFrontPreview()` 中 `delay(2000L)`
- 实际体验较差，可考虑提升到 1fps（1000L），代价是电量消耗增加

**8. `startForegroundService` 后 App 被杀可能导致 capture 丢失**
- 建议：Alarm 触发时立即调用 `startForegroundService`，不要依赖 `LightBotService.onStartCommand` 的通知构建

---

## 七、版本信息

| 组件 | 版本 |
|---|---|
| Min SDK | 24（Android 7.0） |
| Target SDK | 34（Android 14） |
| CameraX | 约 1.3.x（`lifecycle-runtime-ktx` 联动版本） |
| OkHttp | 4.x |
| Kotlin | 1.9.x |
| Gradle | 8.x |
| AGP | 8.x |

---

## 八、关键文件清单

| 文件 | 行数 | 职责 |
|---|---|---|
| `light/LightBotService.kt` | ~650 | 核心：Service + CameraX + Polling + 命令路由 |
| `main/TelegramBot.kt` | ~120 | Telegram API HTTP 封装（OkHttp） |
| `main/Config.kt` | ~120 | SharedPreferences 统一访问层 |
| `light/LightAlarmReceiver.kt` | ~60 | 主监控定时器（AlarmManager） |
| `light/TicketChecker.kt` | ~250 | 澳门告票查询（OkHttp + 正则解析） |
| `light/ServiceStatus.kt` | ~120 | 进程内实时状态收集 |
| `light/LightMainActivity.kt` | ~200 | 主 UI（启动/停止/拍照/告票开关） |
| `light/LightSettingsActivity.kt` | （未含） | Token/ChatID/车牌列表设置页 |
| `full/SentinelService.kt` | ~640 | Full 版 AI 持续监控（YOLOv8n） |
| `full/BotService.kt` | （未含） | Full 版轮询服务 |
