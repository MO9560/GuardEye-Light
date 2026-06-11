# GuardEye 项目全面检视报告

> 生成时间：2026-06-10 20:10
> 检视范围：android-guard-bot 项目完整代码库
> 检视方式：只读分析，未对系统做任何修改

---

## 一、项目概述

**项目名称：** GuardEye  
**一句话定位：** 将 Android 手机变成监控摄像头，通过 Telegram 远程控制拍照，AI 自动识别可疑目标并推送告警。

**核心价值：** 低成本、零订阅、自托管的手机监控方案。

**当前版本：** v4.0（从文档和代码中的 VERSION_NAME 推断）

**技术栈：**
- 语言：Kotlin 1.9.22
- 构建：Gradle 8.4 + AGP 8.2.2
- 最低 SDK：26 (Android 8.0)
- 目标 SDK：34 (Android 14)
- CameraX：1.3.1（full flavor）
- OkHttp：4.12.0
- TensorFlow Lite：2.14.0（full flavor）
- YOLOv8n 模型（full flavor）

---

## 二、产品架构

### 2.1 双 Flavor 设计

| Flavor | 应用名 | 包名后缀 | 说明 |
|---------|---------|----------|------|
| **full** | GuardEye | （无） | 完整功能：CameraX + TFLite + YOLOv8n AI 检测 |
| **light** | GuardEye Light | .light | 轻量版：仅拍照上传，无 AI 检测 |

### 2.2 核心模块

```
Config (SharedPreferences — 单一数据源)
    │
    ├── TelegramBot  ← 无状态工具类：sendText() / sendPhoto() / fetchUpdates()
    │
    ├── BotService   ← 前台 Service，Long Polling 接收 Telegram 命令
    │
    ├── CameraService ← 普通 Service，CameraX 拍照 + YOLOv8n 推理 → 发送 Telegram
    │
    ├── SentinelService ← v4.0 新增：持续 AI 监控（ImageAnalysis 连续帧分析）
    │
    ├── AlarmReceiver ← BroadcastReceiver，定时触发 CameraService
    │
    └── BootReceiver  ← BroadcastReceiver，开机恢复监控
```

### 2.3 进程模型

**单进程设计：** 所有组件运行在主 App 进程。

| Service | 类型 | 生命周期 | 职责 |
|---------|------|----------|------|
| `BotService` | 前台 Service | 持续运行 | Telegram 轮询 + 命令处理 |
| `CameraService` | 普通 Service | 拍完即停 (`STOP_SELF`) | CameraX 拍照 + AI 推理 → 发 Telegram |
| `SentinelService` | 前台 Service | 持续运行（v4.0） | ImageAnalysis 连续帧 + 状态机告警 |

---

## 三、功能清单

### 3.1 Telegram 命令协议

| 命令 | 功能 | 状态 |
|------|------|------|
| `/start` | 启动监控 | ✅ 已实现 |
| `/stop` | 停止监控 | ✅ 已实现 |
| `/photo` | 立即拍照 | ✅ 已实现 |
| `/status` | 查询状态 | ✅ 已实现 |
| `/interval N` | 设置拍照间隔（1-60 分钟） | ✅ 已实现 |
| `/detect on/off` | AI 检测开关 | ✅ 已实现 |
| `/debug on/off` | 调试模式开关 | ✅ 已实现 |
| `/mode photo/video` | 告警模式切换（v4.0） | ✅ 已实现 |
| `/test` | 测试连接 | ✅ 已实现 |

### 3.2 定时拍照

- **实现方式：** `AlarmManager.setExactAndAllowWhileIdle()` + `AlarmReceiver`
- **间隔范围：** 1-60 分钟（UI 限制 1-10 分钟）
- **拍照模式：**
  - 间隔触发 → `CameraService.SOURCE_INTERVAL`
  - 手动触发 → `CameraService.SOURCE_MANUAL`

### 3.3 AI 检测（仅 full flavor）

- **模型：** YOLOv8n (`yolov8n.tflite`)
- **输入：** 640×640 RGB Bitmap
- **输出：** [1, 84, 8400] 张量
- **目标类别：** COCO 80 类
- **告警类别：** person, bicycle, car, motorbike, bus, truck, ambulance, fire hydrant, stop sign
- **状态机：** `SecurityState`（IDLE → GRACE_PERIOD → ALERT_TRIGGERED）

### 3.4 告警模式（v4.0）

| 模式 | 说明 |
|------|------|
| **photo** | 检测到目标 → 发送最新一帧图片 |
| **video** | 检测到目标 → 发送最近 N 秒片段（当前为 photo fallback） |

### 3.5 调试模式

**开启条件：**
- App 内置开关（`Config.debugMode`，默认开启）
- Telegram 远程命令（`/debug on/off`）

**调试信息包括：**
- CameraX 初始化状态
- YOLOv8n 模型加载状态
- 最近拍照时间 + 耗时
- AI 推理耗时
- 图片分辨率 + 文件大小
- 电池电量
- JVM 内存占用
- Bot offset + 连接状态

---

## 四、源代码结构

### 4.1 目录结构

```
D:\qclaw-workspace\android-guard-bot\
├── app\
│   ├── src\
│   │   ├── main\                      # 共享代码
│   │   │   ├── java\com\guardeye\
│   │   │   │   ├── Config.kt        # SharedPreferences 封装
│   │   │   │   └── TelegramBot.kt   # Telegram Bot API 工具类
│   │   │   ├── res\                  # 共享资源（colors, styles, drawable）
│   │   │   └── AndroidManifest.xml   # 主 Manifest（权限声明）
│   │   │
│   │   ├── full\                      # 完整功能 flavor
│   │   │   ├── java\com\guardeye\
│   │   │   │   ├── MainActivity.kt    # 主界面（配置 + 调试面板）
│   │   │   │   ├── BotService.kt     # Telegram 轮询 Service
│   │   │   │   ├── CameraService.kt  # 拍照 + AI 推理 Service
│   │   │   │   ├── SentinelService.kt # v4.0 持续监控 Service
│   │   │   │   ├── Detector.kt      # YOLOv8n 封装
│   │   │   │   ├── AlarmReceiver.kt  # 定时拍照 BroadcastReceiver
│   │   │   │   └── BootReceiver.kt  # 开机恢复 BroadcastReceiver
│   │   │   ├── res\layout\
│   │   │   │   └── activity_main.xml  # 卡片式 UI（浅蓝配色）
│   │   │   └── AndroidManifest.xml  # full flavor 组件注册
│   │   │
│   │   └── light\                     # 轻量版 flavor
│   │       ├── java\com\guardeye\light\
│   │       │   ├── LightMainActivity.kt
│   │       │   ├── LightBotService.kt
│   │       │   ├── CameraForegroundService.kt
│   │       │   ├── LightAlarmReceiver.kt
│   │       │   ├── LightBootReceiver.kt
│   │       │   ├── LightSettingsActivity.kt
│   │       │   ├── StatusActivity.kt
│   │       │   └── ServiceStatus.kt
│   │       └── AndroidManifest.xml
│   │
│   ├── build.gradle.kts              # App 构建配置（flavor 定义）
│   └── proguard-rules.pro
│
├── build.gradle.kts                  # 根构建配置（插件版本）
├── settings.gradle.kts              # 项目设置
├── gradle.properties               # Gradle 属性
├── local.properties                # 本地 SDK 路径
├── signing.properties              # 签名配置（可选）
├── README.md                      # 项目说明（英文）
├── SPEC.md                       # 产品设计规格书（中文，含状态标记）
├── SPEC_COMPARISON.md            # SPEC vs Reality 对比审计（含已知 Bug 分析）
├── UI_REDESIGN_HANDOVER_20260602.md  # UI 重设计交接文档
├── 参考方案总结报告.md             # 参考方案总结（中文）
└── app-light-debug-v4.2.apk    # 轻量版 APK（最新）
```

### 4.2 核心类说明

#### Config.kt（共享）

**职责：** SharedPreferences 封装，所有配置的单一数据源。

**关键属性：**
- `botToken: String` — Telegram Bot Token
- `chatId: String` — Telegram Chat ID
- `intervalMinutes: Int` — 拍照间隔（1-60）
- `enabled: Boolean` — 监控开关
- `detectionEnabled: Boolean` — AI 检测开关
- `debugMode: Boolean` — 调试模式开关
- `cameraFacing: String` — 摄像头方向（"back" / "front"）
- `botOffset: Long` — Telegram getUpdates offset
- `lastIntervalCaptureTime: Long` — 最近间隔拍照时间
- `lastManualCaptureTime: Long` — 最近手动拍照时间
- `lastCaptureSource: String` — 最近拍照来源
- `alertMode: String` — 告警模式（"photo" / "video"）

**设计原则：** 所有 Service 启动时从 `Config.init(ctx)` 读取最新值，不在内存缓存配置。

---

#### TelegramBot.kt（共享）

**职责：** 无状态 Telegram Bot API 工具类。

**关键方法：**
- `sendText(token, chatId, text): Result<Unit>` — 发送文本消息
- `sendPhoto(token, chatId, photoBytes, caption): Result<Unit>` — 发送图片（multipart/form-data）
- `fetchUpdates(token, offset, timeoutSec): Result<List<Update>>` — Long Polling 获取更新

**数据结构：**
```kotlin
data class Update(
    val updateId: Long,
    val chatId: String,
    val text: String
)
```

**网络层：** OkHttp 4.12.0，连接超时 15s，读写超时 30s。

---

#### MainActivity.kt（full）

**职责：** 主界面，配置输入 + 调试面板。

**UI 组件：**
- Bot Token 输入框（`etBotToken`）
- Chat ID 输入框（`etChatId`）
- 拍照间隔拖拽条（`sliderInterval`，1-10 分钟）
- AI Detection 开关（`switchDetection`）
- Debug Mode 开关（`switchDebug`）
- 保存配置按钮（`btnSave`）
- 启动/停止监控按钮（`btnStart`）
- 调试面板（`cardDebug`，可折叠）

**生命周期：**
- `onCreate()` — Config.init → setContentView → preloadModel → initViews → loadConfig → setupListeners
- `onResume()` — 启动调试面板刷新（每 3 秒）
- `onPause()` — 停止调试面板刷新

**关键流程：**
- `startAll()` — 启动 BotService → 延迟 2s 发送启动消息 → 延迟 3s 拍第一张 → 调度 Alarm
- `stopAll()` — 取消 Alarm → 停止 BotService → 停止 CameraService → 发送停止消息

---

#### BotService.kt（full）

**职责：** 前台 Service，Telegram Long Polling 轮询 + 命令处理。

**协程设计：**
- `lifecycleScope` — 轮询循环（绑定 Service 生命周期）
- `cmdScope` — 命令处理（应用级作用域，`SupervisorJob()`）

**已知问题（来自 SPEC_COMPARISON.md）：**
- `lifecycleScope.launch` 在 Service 生命周期销毁时会被取消，可能影响命令处理。
- 修复建议：将 `cmdScope` 改为 Application 级作用域，避免被 lifecycle 取消。

**命令路由：**
- `/start` → 启动 `SentinelService`（v4.0）或 `CameraService`（v3.x）
- `/stop` → 停止 `SentinelService`
- `/photo` → 启动 `CameraService`（手动拍照）
- `/status` → 返回状态报告
- `/interval N` → 设置拍照间隔
- `/detect on/off` → AI 检测开关
- `/debug on/off` → 调试模式开关
- `/mode photo/video` → 告警模式切换
- `/test` → 测试连接

---

#### CameraService.kt（full）

**职责：** 普通 Service，CameraX 拍照 + YOLOv8n 推理 → 发送 Telegram → self-stop。

**设计原则：** 短生命周期，拍完即停（`START_NOT_STICKY`）。

**关键流程：**
1. `captureAndSend()` — 检查 CameraProvider 是否已初始化
2. `doCapture()` — 调用 `ImageCapture.takePicture()`
3. `onCaptureSuccess(imageProxy)` — 转换 Bitmap → 调用 `processAndSend()`
4. `processAndSend(bitmap)` — AI 推理 → 构建 caption → 发送 Telegram → `stopSelf()`

**已知 Bug（来自 SPEC_COMPARISON.md）：**
- **Bug #4：** `imageProxyToBitmap()` 将 YUV_420_888 的 luminance 字节直接喂给 `BitmapFactory.decodeByteArray()`（期望 JPEG 格式），导致 bitmap 损坏或 null。
- **修复建议：** 改用 `YuvImage.compressToJpeg()` 或手动 YUV→RGB 转换。

---

#### SentinelService.kt（full，v4.0 新增）

**职责：** 前台 Service，持续 AI 监控（ImageAnalysis 连续帧分析）。

**架构：**
- CameraX `ImageAnalysis` → 连续帧流
- `RollingFrameBuffer` — 滚动帧缓冲（最近 N 秒）
- `Detector` — YOLOv8n 推理
- `SecurityState` — 状态机（IDLE → GRACE_PERIOD → ALERT_TRIGGERED）
- `AlertHandler` — 根据 `alertMode` 发送图片或视频

**关键参数：**
- `MAX_BUFFER_SECONDS = 10` — 缓冲最近 10 秒帧
- `ALERT_BUFFER_SECONDS = 5` — 告警时发送最近 5 秒帧
- `ALERT_COOLDOWN_MINUTES = 2` — 告警冷却时间
- `GRACE_PERIOD_MS = 5000` — 连续 5 秒检测到目标才触发告警
- `TARGET_FPS = 5` — 分析帧率
- `TARGET_CLASSES = {person, motorbike, bicycle, car, bus, truck}` — 目标类别

**热保护：**
- 使用 `PowerManager.getThermalStatus()` 检测设备温度
- 温度 ≥ 4（`THERMAL_STATUS_SEVERE`）时跳过帧

**告警模式：**
- `PHOTO` — 发送最新一帧（已完成）
- `VIDEO` — 发送最近 N 秒视频（待完成，当前为 photo fallback）

---

#### Detector.kt（full）

**职责：** YOLOv8n TFLite 模型封装。

**模型加载：**
1. 检查 `filesDir/yolov8n.tflite` 是否存在
2. 若不存在，从 `assets/yolov8n.tflite` 复制到 `filesDir`
3. 创建 `Interpreter`（4 线程）
4. 设置 `isReady = true`

**推理流程：**
1. 将 Bitmap 缩放到 640×640
2. 提取 RGB 字节到 `ByteBuffer`（`ByteOrder.nativeOrder()`）
3. 运行 `interpreter.run(inputBuffer, outputBuffer)`
4. 解析输出 `[1, 84, 8400]`：
   - 84 = 4(box) + 80(classes)
   - 8400 = grid cells
5. 非极大值抑制（NMS）未实现，当前为简单阈值过滤（`conf >= 0.5`）

**已知问题（来自 SPEC_COMPARISON.md）：**
- `imageProxyToBitmap()` 转换错误，导致输入 Bitmap 损坏，YOLOv8n 输出零检测结果。
- `Detector.detect()` 的输出解析可能与实际 YOLOv8n 输出格式不完全匹配。

---

#### AlarmReceiver.kt（full）

**职责：** 定时拍照闹钟，接收 `AlarmManager` 广播 → 启动 `CameraService`。

**调度流程：**
1. `scheduleAlarm(ctx, intervalMinutes)` — 创建 `PendingIntent`（广播）
2. 检查 `SCHEDULE_EXACT_ALARM` 权限（Android 12+）
3. 调用 `alarm.setExactAndAllowWhileIdle()` 或降级到 `setAndAllowWhileIdle()`
4. 在 `onReceive()` 中启动 `CameraService`，然后重新调度下一个闹钟

**已知 Bug（来自 SPEC_COMPARISON.md）：**
- **Bug #1：** Android 14+ 要求用户在系统设置中授权 `SCHEDULE_EXACT_ALARM`，仅在 Manifest 声明不够。
- **Bug #2：** `PendingIntent.FLAG_IMMUTABLE` 与 `setExactAndAllowWhileIdle()` 冲突，Android 12+ 要求 `FLAG_MUTABLE`。
- **修复建议：**
  - 在 `MainActivity` 中引导用户跳转 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`
  - 将 `PendingIntent.FLAG_IMMUTABLE` 改为 `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE`

---

#### BootReceiver.kt（full）

**职责：** 开机广播，恢复监控（如果 `Config.enabled == true`）。

**流程：**
1. 接收 `ACTION_BOOT_COMPLETED`
2. 检查 `Config.enabled && Config.isConfigured`
3. 启动 `BotService`
4. 调度 `AlarmReceiver`

---

## 五、构建配置

### 5.1 根 build.gradle.kts

```kotlin
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

### 5.2 App build.gradle.kts

**Flavor 定义：**
```kotlin
flavorDimensions += listOf("version")

productFlavors {
    create("full") {
        dimension = "version"
        applicationIdSuffix = ""
        resValue("string", "app_name", "GuardEye")
        buildConfigField("boolean", "IS_LIGHT", "false")
    }
    create("light") {
        dimension = "version"
        applicationIdSuffix = ".light"
        minSdk = 21
        resValue("string", "app_name", "GuardEye Light")
        buildConfigField("boolean", "IS_LIGHT", "true")
    }
}
```

**依赖关系：**
- **共享（both flavors）：**
  - `androidx.lifecycle:lifecycle-runtime-ktx:2.7.0`
  - `androidx.lifecycle:lifecycle-service:2.7.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
  - `com.squareup.okhttp3:okhttp:4.12.0`
  - `com.google.android.material:material:1.11.0`
  - `androidx.exifinterface:exifinterface:1.3.7`

- **full flavor 专属：**
  - `androidx.camera:camera-core:1.3.1`
  - `androidx.camera:camera-camera2:1.3.1`
  - `androidx.camera:camera-lifecycle:1.3.1`
  - `androidx.camera:camera-view:1.3.1`
  - `org.tensorflow:tensorflow-lite:2.14.0`
  - `org.tensorflow:tensorflow-lite-support:0.4.4`
  - `com.google.code.gson:gson:2.10.1`

- **light flavor 专属：**
  - `androidx.camera:camera-core:1.3.1`
  - `androidx.camera:camera-camera2:1.3.1`
  - `androidx.camera:camera-lifecycle:1.3.1`
  - `androidx.camera:camera-view:1.3.1`

**签名配置：**
- 优先读取 `signing.properties`
-  fallback：`guard-eye.jks`（密码：`GuardEye2026`）

**版本号：**
- `versionCode = git rev-list HEAD --count`（动态）
- `versionName = "2.1." + versionCode`

---

## 六、权限声明

### 6.1 普通权限（无需运行时申请）

- `INTERNET` — 访问网络（Telegram Bot API）
- `RECEIVE_BOOT_COMPLETED` — 开机广播
- `WAKE_LOCK` — 保持 CPU 唤醒（AlarmManager）
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — 忽略电池优化
- `SCHEDULE_EXACT_ALARM` — 精确闹钟（Android 12+，需用户授权）

### 6.2 危险权限（需运行时申请）

- `CAMERA` — 访问摄像头
- `POST_NOTIFICATIONS` — 发送通知（Android 13+）

---

## 七、已知问题汇总

### 7.1 严重 Bug（🔴 Critical）

| # | Bug | 影响 | 修复方案 |
|---|-----|------|----------|
| 1 | **定时拍照不触发** — Android 14+ `SCHEDULE_EXACT_ALARM` 需用户授权 | 定时拍照完全失效 | 在 `MainActivity` 中引导用户跳转 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| 2 | **定时拍照不触发** — `PendingIntent.FLAG_IMMUTABLE` 与 `setExactAndAllowWhileIdle()` 冲突 | 定时拍照完全失效 | 改为 `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE` |
| 3 | **Telegram 命令不响应** — `BotService.startPolling()` 在 `lifecycleScope` 中运行，被 Service 生命周期取消 | 所有命令无响应 | 将轮询循环移到 `cmdScope`（应用级作用域） |
| 4 | **AI 零检测** — `CameraService.imageProxyToBitmap()` 将 YUV 字节误认为 JPEG | AI 检测完全失效 | 改用 `YuvImage.compressToJpeg()` 或手动 YUV→RGB 转换 |
| 5 | **模型加载失败** — `yolov8n.tflite` 可能不在 `assets/` 中 | AI 检测不可用 | 确认模型文件在 `app/src/main/assets/` 中 |

### 7.2 中等问题（🟡 Medium）

| # | Bug | 影响 | 修复方案 |
|---|-----|------|----------|
| 6 | **`/status` 模型状态错误** — 使用 `File.exists()` 而非 `CameraService.isModelReady` | 状态报告不准确 | 改为读取 `CameraService.isModelReady` |
| 7 | **Camera Preview 缺失** — `activity_main.xml` 中无 `PreviewView` | 无法实时预览摄像头 | 在布局中添加 `PreviewView`，并在 `ImageCapture` 绑定时添加 `Preview` 用例 |
| 8 | **Gradle 构建未验证** — UI 重设计后未完整构建 | 可能存在编译错误 | 在 Android Studio 或命令行完成一次完整 build |

### 7.3 低优先级问题（⚪ Low）

| # | Bug | 影响 |
|---|-----|------|
| 9 | **旧 APK 问题** — GitHub Actions 构建需 2-5 分钟，用户手机上可能是旧版本 | 功能不完整 |

---

## 八、UI 设计（v3.1 重设计）

### 8.1 配色方案

| 用途 | 色值 | 说明 |
|------|-------|------|
| 主色 / 强调色 | `#2196F3` | 按钮、SeekBar、链接 |
| 强调色（Start 按钮） | `#039BE5` | 主操作按钮背景 |
| 背景（起始渐变） | `#E3F2FD` | 页面顶部背景 |
| 背景（结束渐变） | `#F0F8FF` | 页面底部背景 |
| 卡片背景 | `#FFFFFF` | 所有 MaterialCardView |
| 卡片描边 | `#C8E6F8` | 卡片边框 |
| 状态绿 | `#22C55E` | Bot 正常运行指示 |
| 状态琥珀 | `#F59E0B` | Bot 未配置 / 警告 |
| 文字主色 | `#1A1A2E` | 标题、正文 |
| 文字次色 | `#6B7280` | 标签、说明文字 |

### 8.2 布局结构

```
ScrollView
├── Header（App 图标 + GuardEye 标题 + 版本徽章）
├── 状态卡片（MaterialCardView）
│   ├── Bot 状态（绿/琥珀色圆点 + 文字）
│   └── 下次拍照时间
├── 配置卡片（MaterialCardView）
│   ├── Bot Token 输入框（TextInputLayout 包裹）
│   ├── Chat ID 输入框（TextInputLayout 包裹）
│   └── 拍照间隔拖拽条（SeekBar）
├── 设置卡片（MaterialCardView）
│   ├── AI Detection 开关
│   └── Debug Mode 开关
├── 操作按钮（Save / Start）
└── 调试面板卡片（可折叠）
```

---

## 九、APK 文件

项目根目录包含以下 APK 文件：

| 文件名 | 说明 |
|--------|------|
| `app-light-debug-v4.1.apk` | Light 版 v4.1（最新） |
| `app-light-debug-v4.2.apk` | Light 版 v4.2（最新） |
| `app-light-debug-v4.apk` | Light 版 v4.0 |
| `guard-eye-light-v2.0.apk` | Light 版 v2.0（旧版） |
| `guard-eye-clean.b64` | GuardEye 全功能版（Base64 编码） |
| `guard-eye.b64` | GuardEye 全功能版（Base64 编码，可能更旧） |

**注意：** `guard-eye-clean.b64` 和 `guard-eye.b64` 需要解码后才能安装：
```bash
base64 -d guard-eye-clean.b64 > guard-eye-clean.apk
```

---

## 十、文档文件

| 文件名 | 说明 |
|--------|------|
| `README.md` | 项目说明（英文），包含功能列表、Setup、架构图、Build 命令 |
| `SPEC.md` | 产品设计规格书（中文），含状态标记（✅/🟡/🔴/⚪），最后更新 2026-05-26 |
| `SPEC_COMPARISON.md` | SPEC vs Reality 对比审计，包含 9 个已知 Bug 的详细分析和修复方案 |
| `UI_REDESIGN_HANDOVER_20260602.md` | UI 重设计交接文档（2026-06-02），包含改动概述、修改文件清单、新布局结构、已知问题、配色参考、下一步建议 |
| `参考方案总结报告.md` | 参考方案总结（中文，内容未读取） |

---

## 十一、待完成功能

### 11.1 Video 告警模式

**当前状态：** `SentinelService.sendVideoAlert()` 未完成，当前为 photo fallback。

**实现思路：**
1. 使用 `MediaCodec` 或 `MediaMuxer` 将 `RollingFrameBuffer` 中的 Bitmap 编码为 MP4
2. 发送 MP4 文件到 Telegram（需要分块上传，Telegram Bot API 限制 50MB）

**推荐方案：**
- 使用 `JCodec`（纯 Java，无 NDK 依赖）或 `FFmpegKit`（需要引入 so 库）
- 或者：将最近 N 帧作为图片集发送（Telegram 支持 MediaGroup）

### 11.2 Camera Preview

**当前状态：** `activity_main.xml` 中无 `PreviewView`，无法实时预览摄像头。

**实现思路：**
1. 在 `activity_main.xml` 中添加 `PreviewView`
2. 在 `MainActivity` 中绑定 `Preview` 用例到 CameraX
3. 注意生命周期管理（`PreviewView` 需要 `ProcessCameraProvider.bindToLifecycle()`）

---

## 十二、优先级修复建议

根据 `SPEC_COMPARISON.md` 和代码分析，建议按以下顺序修复：

1. **[Bug #4]** 修复 `CameraService.imageProxyToBitmap()` — 使用 `YuvImage.compressToJpeg()`
2. **[Bug #5]** 确认 `yolov8n.tflite` 在 `app/src/main/assets/` 中
3. **[Bug #3]** 修复 `BotService` 协程作用域 — 将 `cmdScope` 改为 Application 级
4. **[Bug #1 + #2]** 修复 `AlarmReceiver` — `FLAG_MUTABLE` + 引导用户授权 `SCHEDULE_EXACT_ALARM`
5. **[Bug #6]** 修复 `/status` 模型状态检查 — 使用 `CameraService.isModelReady`
6. **[Bug #8]** 验证 Gradle 构建 — 完成一次完整 build
7. **[Bug #7]** 添加 Camera Preview — 可选（不影响核心功能）

---

## 十三、总结

GuardEye 是一个架构清晰、功能完整的 Telegram 控制 Android 监控项目。当前代码实现了所有核心功能（Telegram 命令、定时拍照、AI 检测、持续监控、告警推送），但存在 5 个严重 Bug 导致部分功能不工作（定时拍照、命令响应、AI 检测）。

**建议下一步：**
1. 修复 5 个严重 Bug（优先级见 §十二）
2. 完成 Video 告警模式（`SentinelService.sendVideoAlert()`）
3. 在真机上完整测试所有功能
4. 更新 `README.md` 和 `SPEC.md` 到 v4.0

---

*本报告由 WorkBuddy AI 自动生成，基于项目源代码和文档文件的全面检视。*
