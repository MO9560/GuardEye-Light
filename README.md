# GuardEye 🛡️

Android 8+ 监控拍照 App，集成 YOLOv8n 智能识别，发现目标即时 Telegram 告警。

## 功能

- 📷 **定时拍照** — 1-10 分钟间隔，自动发 Telegram
- 🤖 **Bot 指令** — `/photo` 立即拍照发高清图
- 🔍 **AI 识别** — YOLOvOv8n 检测 + 颜色分析
- 🚨 **告警** — Telegram 消息 + 标注图片 + 震动
- 🔋 **省电** — AlarmManager 精确唤醒
- 📱 **后台运行** — Foreground Service 常驻

## 识别目标

- 👮 交警制服人员（深蓝色/黑色 + 反光条）
- 🏍️ 警用摩托车（白蓝色车身）

## Bot 指令

| 指令 | 说明 |
|------|------|
| `/start` | 开启监控 |
| `/stop` | 停止监控 |
| `/photo` | 立即拍照（高清） |
| `/status` | 查看状态 |
| `/interval N` | 设置间隔（1-10 分钟） |
| `/detect on/off` | 开关 AI 识别 |

## 配置

1. 创建 Bot：`@BotFather` → `/newbot`
2. 获取 Token，填入 App
3. 发任意消息给 Bot，获取 Chat ID（或填入 @username）
4. 安装 APK，开启监控

## 开发

### 本地构建
```bash
./gradlew assembleDebug
```

### GitHub Actions（自动编译 APK）
Fork 后 Push 到 main 分支即可自动构建。

### 下载模型
YOLOv8n TFLite 模型需放入 `app/src/main/assets/yolov8n.tflite`

## 技术栈

- Kotlin + CameraX + OkHttp + TensorFlow Lite
- AlarmManager + Foreground Service
- Telegram Bot API

## 隐私说明

- 所有照片仅发往你配置的 Telegram Bot
- AI 推理在本地执行，不上传任何数据
- 不收集任何个人信息
