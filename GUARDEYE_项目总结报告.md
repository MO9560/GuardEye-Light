# GuardEye 项目总结报告

**项目名称：** GuardEye（android-guard-bot）  
**生成时间：** 2026-05-23  
**项目路径：** `D:\qclaw-workspace\android-guard-bot\`

---

## 一、项目概述

GuardEye 是一款 Android 8+ 监控拍照 App，集成 YOLOv8n 目标检测模型，能够在检测到特定目标（交警制服人员、警用摩托车）时，通过 Telegram Bot 即时发送告警通知。

**核心定位：** 利用 Android 设备摄像头 + AI 视觉识别，实现无人值守的监控告警系统。

---

## 二、功能清单

| 功能 | 说明 |
|------|------|
| 定时拍照 | 1-10 分钟间隔自动拍照，发送 Telegram |
| 即时拍照 | Bot 指令 `/photo` 立即拍摄高清图 |
| AI 识别 | YOLOv8n 检测 + 颜色分析判断是否警用目标 |
| Telegram 告警 | 检测到目标后发送标注图片 + 震动提示 |
| 省电策略 | AlarmManager 精确唤醒，避免轮询耗电 |
| 后台常驻 | Foreground Service + BootReceiver 开机自启 |

**Bot 指令列表：**

| 指令 | 功能 |
|------|------|
| `/start` | 开启监控 |
| `/stop` | 停止监控 |
| `/photo` | 立即拍照（高清） |
| `/status` | 查看状态（电量、内存、模型加载状态） |
| `/interval N` | 设置拍摄间隔（1-10 分钟） |
| `/detect on/off` | 开关 AI 识别 |

---

## 三、技术架构

### 技术栈

| 组件 | 技术 |
|------|------|
| 开发语言 | Kotlin |
| 摄像头 | CameraX（待实现） |
| 网络通信 | OkHttp 3 |
| AI 推理 | TensorFlow Lite + YOLOv8n |
| 调度唤醒 | AlarmManager（`setExactAndAllowWhileIdle`） |
| 后台运行 | Foreground Service（camera 类型） |
| 消息推送 | Telegram Bot API（`api.telegram.org`） |

### 项目结构

```
android-guard-bot/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/guardeye/
│       │   ├── Config.kt          # 配置管理（SharedPreferences）
│       │   ├── MainActivity.kt     # 主界面（Bot Token / Chat ID 配置）
│       │   ├── CameraService.kt   # 前台服务（核心监控逻辑）
│       │   ├── TelegramBot.kt     # Telegram Bot API 封装
│       │   ├── Detector.kt        # YOLOv8n 检测 + 颜色分析
│       │   ├── AlarmReceiver.kt   # 定时唤醒 BroadcastReceiver
│       │   └── BootReceiver.kt   # 开机自启 BroadcastReceiver
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml, colors.xml, styles.xml
│           └── drawable/edit_bg.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## 四、核心组件分析

### 4.1 Config.kt（配置管理）

- 使用 `SharedPreferences` 持久化配置
- 管理字段：`botToken`、`chatId`、`intervalMinutes`（默认 5）、`enabled`、`detectionEnabled`
- 提供 `isConfigured` 判断是否已经完成 Bot 配置

### 4.2 CameraService.kt（核心服务）

- 继承 `LifecycleService`，支持协程作用域
- `startMonitoring()`：
  1. 创建前台通知（防止系统杀后台）
  2. 加载 YOLOv8n 模型（从 `filesDir/yolov8n.tflite`）
  3. 启动 Bot 轮询（`startBotPolling()`）
  4. 调度下次拍照（`scheduleNextCapture()`）
- `captureAndSend(hd)`：
  1. 调用 `takePicture()` 拍照
  2. 若开启 AI 识别，调用 `Detector.detect()` 分析
  3. 检测到警用目标 → 发送告警（标注图 + 震动）
  4. 高清模式 → 额外发送原图
- **当前问题：** `takePicture()` 未接入 CameraX，仅生成模拟 Bitmap（灰色背景 + 时间戳文字）

### 4.3 Detector.kt（AI 检测器）

- 输入：Bitmap → 预处理为 `640×640` ByteBuffer
- 输出：`List<Detection>`（标签、置信度、边框、是否警用）
- YOLOv8n 输出解析：`[1, 84, 8400]`（4 坐标 + 80 COCO 类别）
- NMS（非极大值抑制）：IoU 阈值 0.45
- **颜色分析逻辑：**
  - 人员（`person`）：判断深蓝色/黑色像素占比（>10%/20%）+ 反光条（>3%）→ 疑似交警制服
  - 摩托车（`motorcycle`）：判断白色 + 蓝色像素同时存在（各 >15%）→ 疑似警用摩托车

### 4.4 TelegramBot.kt（Telegram API 封装）

- `sendText(text)`: 发送文本消息（`sendMessage`）
- `sendPhoto(photoFile, caption)`: 发送图片文件（`sendPhoto`，Multipart）
- `sendBitmap(bitmap, caption)`: 发送 Bitmap 对象（压缩为 JPEG 95% 后上传）
- `getUpdates(offset)`: 轮询 Bot 消息（`getUpdates`），返回 `List<Update>`
- `getChatId(username)`: 通过用户名查询 Chat ID
- OkHttp 超时配置：连接 30s、写入 60s、读取 30s

### 4.5 AndroidManifest.xml（权限声明）

**关键权限：**
- `CAMERA`（必需，硬件要求 `android.hardware.camera`）
- `INTERNET`（Telegram API 通信）
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA`（前台服务）
- `RECEIVE_BOOT_COMPLETED`（开机自启）
- `SCHEDULE_EXACT_ALARM`（精确闹钟）
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（忽略电池优化）
- `VIBRATE`（震动告警）
- `POST_NOTIFICATIONS`（通知权限，Android 13+）

---

## 五、当前状态与问题

### ✅ 已完成

1. 项目结构完整（Gradle 构建配置、Manifest、布局）
2. Telegram Bot 通信模块（发送文本/图片、接收指令）
3. YOLOv8n 检测器框架（TFLite 推理、NMS、颜色分析）
4. 配置管理（SharedPreferences 持久化）
5. 前台服务 + AlarmManager 调度框架
6. Bot 指令处理（`/start`、`/stop`、`/photo`、`/status`、`/interval`、`/detect`）
7. README 文档（功能说明、Bot 配置指引、构建说明）

### ⚠️ 待完成 / 问题

| # | 问题 | 严重程度 | 说明 |
|---|------|----------|------|
| 1 | **CameraX 未接入** | 🔴 严重 | `takePicture()` 仅生成模拟图片，无法实际拍照 |
| 2 | **YOLOv8n 模型文件缺失** | 🔴 严重 | `app/src/main/assets/yolov8n.tflite` 需手动下载放入 |
| 3 | **Detector.kt 输出维度可能错误** | 🟡 中等 | 注释写 `[1, 84, 8400]`，但 COCO 80 类 + 4 坐标 + 1 置信度 = 85，应为 `[1, 85, 8400]` |
| 4 | **Bot 轮询间隔固定 3 秒** | 🟢 低 | `delay(3000)` 硬编码，建议改为可配置 |
| 5 | **未处理通知权限请求（Android 13+）** | 🟡 中等 | `POST_NOTIFICATIONS` 需在运行时请求用户授权 |
| 6 | **电池优化未实际忽略** | 🟡 中等 | 声明了 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，但未调用系统 API 请求忽略 |

---

## 六、改进建议

### 优先级 P0（必须修复）

1. **接入 CameraX**
   - 在 `CameraService.kt` 中实现 `takePicture()` 真实拍照
   - 参考：`ImageCapture` + `CameraX` 绑定生命周期
   - 输出：JPEG 文件到 `cacheDir`

2. **修复 Detector.kt 输出维度**
   - 确认 YOLOv8n TFLite 模型输出格式
   - 若输出为 `[1, 84, 8400]`，则类别索引需调整（注释写 80 类 + 4 + 1 = 85，代码用 5 偏移）

### 优先级 P1（建议修复）

3. **请求通知权限（Android 13+）**
   - 在 `MainActivity.kt` 中添加 `POST_NOTIFICATIONS` 运行时权限请求

4. **请求忽略电池优化**
   - 调用 `Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)` 或 `requestIgnoreBatteryOptimizations()`

### 优先级 P2（体验优化）

5. **Bot 轮询间隔可配置**
   - 在 `Config.kt` 中添加 `pollIntervalSeconds` 字段
   - `CameraService.kt` 中 `delay(pollIntervalSeconds * 1000)`

6. **添加模型下载引导**
   - 在 App 首次启动时检测 `yolov8n.tflite` 是否存在
   - 不存在则跳转下载页面或显示指引

---

## 七、安全性说明

根据 README.md 声明：

- 所有照片仅发往用户配置的 Telegram Bot（非第三方服务器）
- AI 推理在本地执行（TensorFlow Lite），不上传任何数据
- 不收集任何个人信息

**建议补充：**
- `botToken` 在 `SharedPreferences` 中以明文存储，建议加密（Android Keystore）
- `chatId` 同理，虽风险较低但建议统一加密存储

---

## 八、总结

GuardEye 项目框架完整，核心逻辑（Telegram 通信、AI 检测、调度服务）已实现，**主要缺失是 CameraX 实际拍照功能**。若接入 CameraX 并修复 Detector 输出维度问题，即可实机运行。

**推荐下一步：**
1. 接入 CameraX 实现真实拍照
2. 下载 YOLOv8n TFLite 模型放入 `assets/`
3. 实机测试并修复 Detector 输出解析逻辑

---

*报告由 WorkBuddy R 自动生成（2026-05-23）*
