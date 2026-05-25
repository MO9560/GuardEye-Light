# GuardEye — 产品设计规格书

> 所有后续开发必须符合本文件。如需变更，先改此文件，再改代码。

---

## 一、产品定位

**一句话：** 手机变成监控摄像头，Telegram 控制拍照，AI 自动识别可疑目标并推送告警。

**核心价值：** 低成本、零订阅、自托管的手机监控方案。

---

## 二、功能范围

### 2.1 必做（MVP）

| 功能 | 说明 |
|---|---|
| Token 配置 | UI 输入 Bot Token + Chat ID，保存到 SharedPreferences |
| Telegram 发送 | 给定 token/chatId，能发文字 + 照片 |
| 定时拍照 | AlarmManager 定时触发 → CameraX 拍一张 → 发到 Telegram |
| AI 识别 | YOLOv8n 检测照片中的目标（人、车、动物等） |
| 告警推送 | 检测到特定目标（警车/警察/可疑人员）→ 仅推送到 Telegram |
| Telegram 命令 | `/start` `/stop` `/photo` `/status` |

### 2.2 明确不做

| 功能 | 原因 |
|---|---|
| 本地震动/响铃 | 只需要远程告警，减少耗电 |
| 本地通知栏高优先级告警 | Telegram 通知已足够 |
| 心跳保活消息 | 增加复杂度，调试阶段不需要 |
| 多手机管理 | MVP 单一设备 |
| 云端存储/截图历史 | 超出 MVP 范围 |

### 2.3 调试模式（开发阶段）

**开启条件：** App 内置 debug 开关（默认打开，打 release 时关闭）

**调试信息（UI 显示 + Telegram 同步推送）：**

```
[CameraX] 初始化耗时: 1234ms / 状态: OK
[YOLO] 模型加载耗时: 567ms / 内存占用: 45MB
[Alarm] 下次拍照: 19:30:00 / 间隔: 5分钟
[Bot] 连接状态: ✅ / offset: 12345
[Network] 最近请求耗时: 234ms
[Photo] 最近拍照: 19:25:00 / 大小: 512KB
[Memory] JVM 占用: 78MB / 可用: 234MB
```

---

## 三、用户界面

### 3.1 只有一个页面（极简）

```
┌─────────────────────────┐
│  🛡️ GuardEye v3.0       │  ← 标题栏
├─────────────────────────┤
│  ┌─────────────────────┐│
│  │   📷 相机预览       ││  ← SurfaceView（横屏居中）
│  │                     ││
│  └─────────────────────┘│
├─────────────────────────┤
│  Bot Token    [________]│
│  Chat ID      [________]│
│  拍摄间隔  [────●───] 5分│
│  AI识别    [  ON  ]    │  ← Switch
│  调试模式  [  ON  ]    │  ← Switch
├─────────────────────────┤
│  [保存配置]   [启动监控]│
├─────────────────────────┤
│  📊 调试面板           │  ← 展开/收起
│  CameraX: ✅ 正常       │
│  模型: ✅ 已加载(1.2s)  │
│  Bot: ✅ 运行中         │
│  下次拍照: 19:30:00    │
│  offset: 12345         │
│  JVM: 78MB / 234MB     │
└─────────────────────────┘
```

### 3.2 按钮行为

| 按钮 | 行为 |
|---|---|
| 保存配置 | 把 Token/ChatID/间隔/detection/debug 写入 SharedPreferences |
| 启动监控 | 验证 Token 非空 → 启动 BotService + 立即拍第一张 |

---

## 四、架构设计

### 4.1 进程模型

**单进程，所有代码在主 App 进程。**

Service 分两种：
- `BotService`：前台 Service，负责 Telegram 轮询 + 命令处理
- `CameraService`：普通 Service（非前台），负责 CameraX 拍照 + AI 识别，拍照后立即 self-stop

### 4.2 模块职责

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

### 4.3 服务间通信

| 场景 | 方案 |
|---|---|
| BotService 通知 CameraService 立即拍照 | `startService(Intent(ACTION_CAPTURE))` |
| CameraService 拍照完成通知 BotService | **不通知**，CameraService 直接用 TelegramBot 发，BotService 只管收命令 |
| UI 查询服务状态 | 直接读 Config + 文件状态，**不用观察者模式**，简化 |

### 4.4 配置数据流

```
SharedPreferences (Config.kt)
  │
  ├── MainActivity 读取 → 显示 UI
  ├── MainActivity 写入 ← 保存按钮
  │
  ├── BotService 启动时读取 → Config.botToken / Config.chatId
  │                      → 读取后不再缓存（每次操作直接读）
  │
  └── CameraService 启动时读取 → Config.intervalMinutes
                              → Config.detectionEnabled
                              → Config.botToken / Config.chatId（直接发 Telegram）
```

**原则：所有服务启动时从 Config 读最新值，不在内存缓存配置。**

---

## 五、Bot 命令协议

| 命令 | Bot 回复 |
|---|---|
| `/start` | ✅ GuardEye 已启动 + 当前状态摘要 |
| `/stop` | ⏸ GuardEye 已停止 |
| `/photo` | 📸 正在拍照... → 发送照片 + 检测结果 |
| `/status` | 完整状态报告（含调试信息） |
| `/debug on` / `/debug off` | 开启/关闭调试信息推送 |
| `/interval N` | ⏱ 间隔已设为 N 分钟（1-10） |
| `/detect on` / `/detect off` | AI 识别开关 |

---

## 六、调试信息规范

### 6.1 调试信息内容

每次拍照完成后，Bot 推送：

```
📸 GuardEye 拍照报告
─────────────────────
🕐 时间: 2026-05-25 20:30:00
📐 分辨率: 1920×1080
⏱ 拍照耗时: 123ms
🧠 AI 推理耗时: 456ms
📦 图片大小: 512KB
🔍 检测结果: 2个目标
  - person 87%
  - car 72%
⚠️ 告警: 无
─────────────────────
[JVM] 78MB / 234MB
[CameraX] 初始化: OK
```

### 6.2 调试信息开关

- `Config.debugMode`：本地开关
- `/debug on/off`：远程开关
- 两者联动，任意一个打开就推送完整调试信息

---

## 七、技术选型

| 组件 | 选择 | 版本 |
|---|---|---|
| 语言 | Kotlin | 1.9.x |
| CameraX | camera2 | 1.3.x |
| HTTP | OkHttp | 4.12.x |
| AI 推理 | TensorFlow Lite | 2.14.x |
| 模型 | YOLOv8n (yolov8n.tflite) | - |
| 最小 SDK | 26 (Android 8.0) | - |
| 目标 SDK | 34 | - |
| 构建 | Gradle 8.4 / AGP 8.2 | - |

---

## 八、不做本地告警

**含义：** 检测到目标后，**不**触发手机震动、不发本地高优先级通知、不响铃。

**原因：**
- 减少耗电
- 手机通常在口袋里，不需要本地告警
- Telegram 推送已足够远程感知

**实现：** `CameraService.triggerAlert()` → 删掉震动和通知代码，只保留 `BotManager.sendText()` / `BotManager.sendPhoto()` 告警推送。

---

## 九、验收标准（MVP 完成）

- [ ] Token + Chat ID 保存后重启 App 仍然保留
- [ ] 点"启动监控" → Telegram 收到启动消息
- [ ] 5分钟后（定时）→ Telegram 收到照片
- [ ] `/photo` → 立即收到照片
- [ ] `/status` → 收到状态报告
- [ ] `/stop` → 停止定时拍照
- [ ] AI 检测到目标 → Telegram 收到告警文字
- [ ] 调试信息在 UI 和 Bot 同时可见
- [ ] App 退后台 30 分钟仍在运行（前台通知存在）
- [ ] `./gradlew assembleDebug` 成功

---

## 十、已知的坑（v2.0）

| 坑 | 预防措施 |
|---|---|
| 改 Token 后 Bot 不响应 | 所有服务启动时读 Config，不缓存 token |
| /stop 后 Bot 继续运行 | /stop 同时停 BotService 和 CameraService |
| /interval 改了不立即生效 | 改变量后立即重新 scheduleNextCapture() |
| Bot/Camera 互相不知道状态 | 分离设计，CameraService 用 TelegramBot 直发，不依赖 BotService |
| cameraServiceRef 从不被赋值 | 不设跨服务引用，用 Intent 通信 |
| 告警时震动耗电 | 不做本地震动/通知 |
