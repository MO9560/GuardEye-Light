# GuardEye — 产品设计规格书

> 所有后续开发必须符合本文件。如需变更，先改此文件，再改代码。
> **最后更新：** 2026-05-26（v3.1，整合 spec-vs-reality 审计结果）

---

## 状态标记说明

| 标记 | 含义 |
|---|---|
| ✅ | 已实现且工作正常 |
| 🟡 | 部分实现或有降级问题 |
| 🔴 | 已实现但不工作（已知 bug 待修复） |
| ⚪ | 未实现（MVP 范围外） |

---

## 一、产品定位 ✅

**一句话：** 手机变成监控摄像头，Telegram 控制拍照，AI 自动识别可疑目标并推送告警。

**核心价值：** 低成本、零订阅、自托管的手机监控方案。

---

## 二、功能范围

### 2.1 必做（MVP）

| 功能 | 状态 | 备注 |
|---|---|---|
| Token 配置 | ✅ | UI 输入 Bot Token + Chat ID，保存到 SharedPreferences |
| Telegram 发送 | ✅ | `TelegramBot.sendText()` + `sendPhoto()` |
| 定时拍照 | 🔴 | `AlarmReceiver` → `CameraService` 有两个 bug（见 §10） |
| AI 识别 | 🔴 | `Detector.kt` 已实现但 YUV→RGB 转换有 bug，导致零检测结果 |
| 告警推送 | 🟡 | `hasAlert()` + `TelegramBot.sendText()` 逻辑正确，但受 AI 检测 bug 影响 |
| Telegram 命令 | 🔴 | 9 条命令均已实现，但 `BotService` 协程作用域有 bug，命令不响应 |
| CameraX 拍照 | 🟡 | 拍照流程正确，但 `imageProxyToBitmap()` 有 bug（见 §10 Bug #7） |

### 2.2 明确不做 ✅

| 功能 | 状态 |
|---|---|
| 本地震动/响铃 | ✅ 已排除，无相关代码 |
| 本地通知栏高优先级告警 | ✅ 已排除 |
| 心跳保活消息 | ⚪ 未实现（调试阶段不需要） |
| 多手机管理 | ⚪ 未实现 |
| 云端存储/截图历史 | ⚪ 未实现 |

### 2.3 调试模式（开发阶段）🟡

**开启条件：** App 内置 debug 开关（默认打开，打 release 时关闭）

**调试信息格式（当前状态 — 🟡 格式基本正确，但模型状态检查有误）：**

```
[CameraX] 初始化耗时: 1234ms / 状态: OK
[YOLO] 模型加载耗时: 567ms / 内存占用: 45MB
[Alarm] 下次拍照: 19:30:00 / 间隔: 5分钟
[Bot] 连接状态: ✅ / offset: 12345
[Network] 最近请求耗时: 234ms
[Photo] 最近拍照: 19:25:00 / 大小: 512KB
[Memory] JVM 占用: 78MB / 可用: 234MB
```

> ⚠️ **已知问题：** `/status` 中模型状态使用 `File.exists()` 检查而非 `CameraService.isModelReady`（见 §10 Bug #8）。

---

## 三、用户界面 🟡

### 3.1 只有一个页面（极简）

```
┌─────────────────────────┐
│  🛡️ GuardEye v3.0       │
├─────────────────────────┤
│  ┌─────────────────────┐│
│  │   📷 相机预览       ││  ← 🔴 SurfaceView 未实现（见 §10 Bug #9）
│  │                     ││
│  └─────────────────────┘│
├─────────────────────────┤
│  Bot Token    [________]│  ✅
│  Chat ID      [________]│  ✅
│  拍摄间隔  [────●───] 5分│  ✅
│  AI识别    [  ON  ]    │  ✅
│  调试模式  [  ON  ]    │  ✅
├─────────────────────────┤
│  [保存配置]   [启动监控]│  ✅
├─────────────────────────┤
│  📊 调试面板           │  ✅（每 3 秒刷新）
│  CameraX: ✅ 正常       │
│  模型: ❌ 未加载/✅ 已加载│  🟡（预加载后正常）
│  Bot: ✅ 运行中         │
│  下次拍照: 19:30:00    │
│  offset: 12345         │
│  JVM: 78MB / 234MB     │
└─────────────────────────┘
```

### 3.2 按钮行为 ✅

| 按钮 | 行为 | 状态 |
|---|---|---|
| 保存配置 | Token/ChatID/间隔写入 SharedPreferences | ✅ |
| 启动监控 | 验证 Token → 启动 BotService → 2秒后拍第一张 | ✅ |

---

## 四、架构设计

### 4.1 进程模型 ✅

**单进程，所有代码在主 App 进程。**

| Service | 类型 | 职责 | 状态 |
|---|---|---|---|
| `BotService` | 前台 Service | Telegram 轮询 + 命令处理 | 🟡 有 bug（见 §10） |
| `CameraService` | 普通 Service | CameraX 拍照 + AI 推理 → 发 Telegram → self-stop | 🟡 有 bug（见 §10） |

### 4.2 模块职责 ✅

```
Config (SharedPreferences 单一数据源)
    │
    ├── TelegramBot (工具类，不持有状态)
    │       sendText(token, chatId, text)
    │       sendPhoto(token, chatId, bytes, caption)
    │
    ├── BotService (前台 Service)
    │       Long Polling 线程独立
    │       命令: /start /stop /photo /status
    │       /start → 触发 CameraService 立即拍照
    │
    └── CameraService (普通 Service)
            CameraX 初始化
            YOLOv8n 推理
            拍照完成 → TelegramBot.sendPhoto()
            → self.stopSelf() ← 拍完就停，不常驻
            被 AlarmManager 定期唤醒
            被 /photo 命令立即唤醒

AlarmReceiver (BroadcastReceiver)
    → 定时器触发
    → start CameraService (ACTION_CAPTURE)
```

### 4.3 服务间通信 ✅

| 场景 | 方案 | 状态 |
|---|---|---|
| BotService → CameraService | `startService(Intent(ACTION_CAPTURE))` | ✅ |
| CameraService → BotService | **不通知**，CameraService 直接用 TelegramBot 发 | ✅ |
| UI → 服务状态 | 直接读 Config + 文件状态 | ✅ |

### 4.4 配置数据流 ✅

所有服务启动时从 `Config` 读最新值，不在内存缓存配置。✅

---

## 五、Bot 命令协议 ✅（实现完整，但运行时有问题）

| 命令 | Bot 回复 | 状态 |
|---|---|---|
| `/start` | ✅ GuardEye 已启动 + 当前状态摘要 | 🟡 实现正确但命令不响应 |
| `/stop` | ⏸ GuardEye 已停止 | 🟡 同上 |
| `/photo` | 📸 正在拍照... → 发送照片 + 检测结果 | 🟡 同上 |
| `/status` | 完整状态报告（含调试信息） | 🟡 同上，且模型状态检查有误 |
| `/debug on` / `/debug off` | 开启/关闭调试信息推送 | 🟡 同上 |
| `/interval N` | ⏱ 间隔已设为 N 分钟（1-10） | 🟡 同上 |
| `/detect on` / `/detect off` | AI 识别开关 | 🟡 同上 |

---

## 六、调试信息规范 🟡

### 6.1 调试信息内容

拍照完成后 Bot 推送：

```
📸 GuardEye 拍照报告
─────────────────────
🕐 时间: 2026-05-25 20:30:00
📐 分辨率: 1920×1080        ← 🔴 摄像头配置中未实现 PreviewView，分辨率依赖 CameraX 默认值
⏱ 拍照耗时: 123ms
🧠 AI 推理耗时: 456ms
📦 图片大小: 512KB
🔍 检测结果: 2个目标         ← 🔴 当前因 YUV→RGB bug 永远显示 0 个目标
  - person 87%
  - car 72%
⚠️ 告警: 无
─────────────────────
[JVM] 78MB / 234MB
[CameraX] 初始化: OK
```

### 6.2 调试信息开关 ✅

- `Config.debugMode`：本地开关
- `/debug on/off`：远程开关
- 两者联动，任意一个打开就推送完整调试信息

---

## 七、技术选型 ✅

| 组件 | 选择 | 版本 | 状态 |
|---|---|---|---|
| 语言 | Kotlin | 1.9.x（实际 1.9.22） | ✅ |
| CameraX | camera2 | 1.3.x（实际 1.3.1） | ✅ |
| HTTP | OkHttp | 4.12.x（实际 4.12.0） | ✅ |
| AI 推理 | TensorFlow Lite | 2.14.x（实际 2.14.0） | ✅ |
| 模型 | YOLOv8n (yolov8n.tflite) | — | 🟡 模型文件需确认在 assets 中 |
| 最小 SDK | 26 (Android 8.0) | — | ✅ |
| 目标 SDK | 34 | — | ✅ |
| 构建 | Gradle 8.4 / AGP 8.2 | — | ✅ |

---

## 八、不做本地告警 ✅

**含义：** 检测到目标后，**不**触发手机震动、不发本地高优先级通知、不响铃。

**实现：** `CameraService.triggerAlert()` → 只调用 `TelegramBot.sendText()`，无震动代码。✅

---

## 九、验收标准（MVP 完成度）

- [ ] Token + Chat ID 保存后重启 App 仍然保留 — ✅ `SharedPreferences` 正常
- [ ] 点"启动监控" → Telegram 收到启动消息 — 🟡 逻辑正确，依赖命令系统
- [ ] **5分钟后（定时）→ Telegram 收到照片 — 🔴 PendingIntent FLAG_MUTABLE 缺失 + Android 14 权限问题**
- [ ] **`/photo` → 立即收到照片 — 🔴 BotService 协程 bug + CameraService YUV bug**
- [ ] **`/status` → 收到状态报告 — 🔴 BotService 协程 bug**
- [ ] **`/stop` → 停止定时拍照 — 🔴 BotService 协程 bug**
- [ ] AI 检测到目标 → Telegram 收到告警文字 — 🔴 YUV→RGB bug 导致零检测
- [ ] 调试信息在 UI 和 Bot 同时可见 — 🟡 UI 正常，Bot 依赖命令系统；模型状态检查有误
- [ ] App 退后台 30 分钟仍在运行 — 🟡 前台 Service 存在，未实测
- [ ] `./gradlew assembleDebug` 成功 — ✅ 构建正常（1 个 deprecation warning）

---

## 十、已知的坑（v3.0 实际发现）

> 以下为代码审计 + 运行测试后发现的真实问题，按优先级排序。

| # | 坑 | 严重度 | 预防/修复措施 |
|---|---|---|---|
| 🔴 1 | **定时拍照不触发** — `PendingIntent.FLAG_IMMUTABLE` 与 `setExactAndAllowWhileIdle()` 冲突；Android 12+ 要求 `FLAG_MUTABLE` | 修复：`AlarmReceiver.scheduleAlarm()` 改为 `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE` |
| 🔴 2 | **定时拍照不触发** — Android 14+ 对 `SCHEDULE_EXACT_ALARM` 要求用户在系统设置中授权，仅在 Manifest 声明不够 | 修复：在 MainActivity 中引导用户跳转 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| 🔴 3 | **Telegram 命令不响应** — `BotService.startPolling()` 在 `lifecycleScope` 中运行，`lifecycleScope` 被销毁时即使有 `NonCancellable` 也无法保护 | 修复：将轮询循环移到 `cmdScope`（应用级作用域），避免被 Service 生命周期取消 |
| 🔴 4 | **AI 零检测** — `CameraService.imageProxyToBitmap()` 将 YUV_420_888 的 luminance 字节直接喂给 `BitmapFactory.decodeByteArray()`（期望 JPEG 格式），产生 corrupt/null bitmap | 修复：改用 `ImageAnalysis` + `YuvToRgbConverter`，或手动 YUV→RGB 转换 |
| 🔴 5 | **模型显示未加载** — `yolov8n.tflite` 可能不在 `app/src/main/assets/` 中，`Detector.load()` 抛异常后 `isReady = false` | 修复：确认 assets 目录有模型文件；添加 `Log.e(TAG, "MODEL FILE MISSING FROM ASSETS")` 便于排查 |
| 🟡 6 | **`/status` 模型状态错误** — `pushStatus()` 使用 `modelFile.exists()`（检查 filesDir）而非 `CameraService.isModelReady`（实际加载状态） | 修复：`pushStatus()` 中改为读取 `CameraService.isModelReady` |
| 🟡 7 | **CameraX 分辨率** — 布局中无 `PreviewView`，无法控制拍照分辨率，依赖 CameraX 默认选择 | 修复：在 `activity_main.xml` 中添加 `PreviewView`，并在 `ImageCapture` 绑定 `Preview` 用例 |
| 🟡 8 | **旧 APK 问题** — GitHub Actions 构建需 2-5 分钟，用户手机上可能是旧版本 | 预防：等待 Actions 完成或本地 `.\gradlew assembleDebug` 直接安装 |
| ⚪ 9 | Camera Preview（SurfaceView）未实现 | 低优先级，不影响核心监控功能 |

### 优先级修复顺序

1. **Bug #4** — 修 YUV→RGB（影响核心 AI 功能）
2. **Bug #5** — 确认模型文件在 assets
3. **Bug #3** — 修 BotService 协程（让命令响应）
4. **Bug #1 + #2** — 修 AlarmReceiver 权限 + FLAG_MUTABLE
5. **Bug #6** — 修 `/status` 模型状态检查
6. **Bug #7** — 添加 Camera Preview（可选）
