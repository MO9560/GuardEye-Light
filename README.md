# GuardEye v3.0

Telegram-controlled Android surveillance camera with AI detection.

## Features

- **Telegram control** — `/start` `/stop` `/photo` `/status` `/interval N` `/detect on/off` `/debug on/off`
- **Scheduled capture** — AlarmManager triggers photo at configurable intervals (1–10 min)
- **AI detection** — YOLOv8n model runs on-device, detects persons/vehicles/suspicious targets
- **Alert push** — Detection results sent directly to your Telegram chat
- **Debug mode** — Full telemetry (timing, memory, camera status) in-app and via bot

## Setup

1. Create a bot via [@BotFather](https://t.me/BotFather), get the token
2. Start a chat with your bot, send `/start`, get your `chat_id` from [IDBot](https://t.me/myidbot)
3. Install the APK, fill in Token + Chat ID, tap "Start Monitoring"

## Architecture

```
Config (SharedPreferences — single source of truth)
    │
    ├── TelegramBot  ← stateless utility: sendText(), sendPhoto()
    │
    ├── BotService   ← Foreground Service, long-polls Telegram, handles commands
    │
    └── CameraService ← Regular Service, CameraX + YOLOv8n, captures & sends, then stops

AlarmReceiver ← wakes CameraService on schedule
```

## Build

```bash
./gradlew assembleDebug
```

## Debug Mode

Enables verbose telemetry in the app UI and Telegram bot responses:
timing, memory usage, model load time, network latency, detection results.
