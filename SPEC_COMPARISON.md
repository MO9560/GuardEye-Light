# SPEC vs Reality — GuardEye v3.0
> No changes. Pure comparison. Updated: 2026-05-26

---

## Legend
| Color | Meaning |
|---|---|
| 🟢 | Working as specified |
| 🟡 | Partial / degraded / workaround in place |
| 🔴 | Broken / not working |
| ⚪ | Not yet implemented (out of scope) |

---

## §1 Product Positioning — 🟢
| Spec | Reality |
|---|---|
| 手机变成监控摄像头，Telegram 控制拍照，AI 自动识别可疑目标 | ✅ Implemented |

---

## §2 Functional Scope (MVP)

| Feature | Spec | Reality | Status |
|---|---|---|---|
| Token + Chat ID config in UI | §2.1 | ✅ `MainActivity` | 🟢 |
| Telegram send text + photo | §2.1 | ✅ `TelegramBot.sendText/sendPhoto` | 🟢 |
| Timed capture (AlarmManager → CameraX → Telegram) | §2.1 | ✅ `AlarmReceiver` → `CameraService` | 🟡 Partial |
| AI detection (YOLOv8n) | §2.1 | ✅ `Detector.kt` | 🟡 Partial — model loads but detection produces no results |
| Alert push to Telegram | §2.1 | ✅ `hasAlert()` + `TelegramBot.sendText` | 🟡 Partial |
| Telegram commands | §2.1 | ✅ All 9 commands in `handleCommand()` | 🔴 Not working |
| No local vibration | §8 | ✅ No vibration code | 🟢 |

---

## §3 User Interface

| Spec Item | Reality | Status |
|---|---|---|
| Single page with all controls | `activity_main.xml` | 🟡 Debug panel only, no camera preview |
| Token + Chat ID inputs | ✅ | 🟢 |
| Interval slider (1–10 min) | ✅ `sliderInterval` | 🟢 |
| AI detect switch | ✅ `switchDetection` | 🟢 |
| Debug mode switch | ✅ `switchDebug` | 🟢 |
| Save + Start buttons | ✅ `btnSave`, `btnStart` | 🟢 |
| Debug panel (CameraX, model, bot status, offset, JVM) | ✅ `refreshDebugPanel()` | 🟢 |
| **Camera preview (SurfaceView)** | ❌ NOT in layout | 🔴 Missing |

> **Spec §3 says:** A `SurfaceView` camera preview should be in the layout.
> **Reality:** The layout has no `PreviewView`. The camera is only accessed during capture.
> **Impact:** Low (not required for core function), but spec violation.

---

## §4 Architecture

### 4.1 Process Model

| Spec | Reality | Status |
|---|---|---|
| Single process | ✅ All in main app process | 🟢 |
| `BotService` = foreground service for Telegram polling | ✅ `LifecycleService` + `startForeground` | 🟢 |
| `CameraService` = short-lived, captures → sends → stops | ✅ `START_NOT_STICKY`, `stopSelf()` after capture | 🟢 |
| `AlarmReceiver` = `BroadcastReceiver` | ✅ `BroadcastReceiver` | 🟡 See Alarm section |

### 4.2 Module Responsibilities

| Module | Spec duty | Reality | Status |
|---|---|---|---|
| `Config` | SharedPreferences wrapper, read on every service start | ✅ `init(ctx)` required | 🟢 |
| `TelegramBot` | Stateless, `sendText`, `sendPhoto`, `fetchUpdates` | ✅ All three methods | 🟢 |
| `BotService` | Long-polling, `/start /stop /photo /status` | ✅ `startPolling()` + `handleCommand()` | 🟡 See Command section |
| `CameraService` | CameraX → AI → send → stop | ✅ `captureAndSend()` | 🟡 See Model section |
| `AlarmReceiver` | `setExactAndAllowWhileIdle` → start CameraService | ✅ `scheduleAlarm()` + `onReceive()` | 🔴 See Alarm section |
| `BootReceiver` | Restore services on boot | ✅ `ACTION_BOOT_COMPLETED` | 🟢 |

### 4.3 Inter-service Communication

| Scenario | Spec | Reality | Status |
|---|---|---|---|
| BotService → CameraService | `startService(Intent(ACTION_CAPTURE))` | ✅ Implemented | 🟢 |
| CameraService → BotService | Do NOT notify; use TelegramBot directly | ✅ `TelegramBot.sendPhoto()` | 🟢 |
| UI → Service status | Read Config + file state directly | ✅ `refreshDebugPanel()` | 🟢 |

### 4.4 Config Data Flow

| Access | Spec | Reality | Status |
|---|---|---|---|
| All services call `Config.init(ctx)` on start | §4.4 | ✅ `MainActivity`, `BotService.onCreate()`, `AlarmReceiver.onReceive()` | 🟢 |
| No token caching; read fresh every time | §4.4 | ✅ `Config.botToken` read in each method | 🟢 |

---

## §5 Bot Command Protocol

| Command | Spec reply | Reality `handleCommand()` | Status |
|---|---|---|---|
| `/start` | ✅ status + summary | ✅ Implemented | 🟢 |
| `/stop` | ⏸ stopped | ✅ + `AlarmReceiver.cancelAlarm()` | 🟢 |
| `/photo` | 📸 + photo + detection | ✅ Fires `CameraService.ACTION_CAPTURE` | 🟢 |
| `/status` | Full status report | ✅ `pushStatus()` | 🟡 Works but model status uses `File.exists()` not actual detector state |
| `/debug on/off` | Toggle debug | ✅ `Config.debugMode` | 🟢 |
| `/interval N` | ⏱ N minutes | ✅ `AlarmReceiver.scheduleAlarm()` | 🟢 |
| `/detect on/off` | 🔍 toggle | ✅ `Config.detectionEnabled` | 🟢 |
| `/test` | (not in spec) | ✅ Bonus command | 🟢 |

**Overall §5 verdict: ✅ All commands implemented.**

---

## §6 Debug Info

| Item | Spec | Reality | Status |
|---|---|---|---|
| `Config.debugMode` local toggle | §6.2 | ✅ Default `true` in `Config` | 🟢 |
| `/debug on/off` remote toggle | §6.2 | ✅ `handleCommand()` | 🟢 |
| Debug block in caption | §6.1 | ✅ `debugBlock` in `processAndSend()` | 🟢 |
| Debug block in `/status` | §6.1 | ✅ `pushStatus()` | 🟢 |
| Debug panel in UI | §3 UI | ✅ `refreshDebugPanel()` every 3s | 🟢 |

---

## §7 Tech Stack

| Component | Spec | Reality | Status |
|---|---|---|---|
| Kotlin | 1.9.x | ✅ `1.9.22` | 🟢 |
| CameraX camera2 | 1.3.x | ✅ `1.3.1` | 🟢 |
| OkHttp | 4.12.x | ✅ `4.12.0` | 🟢 |
| TensorFlow Lite | 2.14.x | ✅ `2.14.0` | 🟢 |
| YOLOv8n (yolov8n.tflite) | — | ✅ In assets | 🟢 |
| minSdk | 26 | ✅ `26` | 🟢 |
| targetSdk | 34 | ✅ `34` | 🟢 |
| Gradle | 8.4 / AGP 8.2 | ✅ Gradle 8.4 / AGP 8.2 | 🟢 |

---

## §8 No Local Alerts

| Spec | Reality | Status |
|---|---|---|
| No vibration, no local high-priority notification | §8 | ✅ Only `TelegramBot.sendText()` for alerts | 🟢 |

---

## §9 Acceptance Criteria

| # | Criterion | Status | Notes |
|---|---|---|---|
| 1 | Token + Chat ID persist after restart | 🟢 | `SharedPreferences` in `Config.kt` |
| 2 | Start → Telegram receives startup message | 🟡 | `MainActivity.startAll()` sends after 2s delay; old APK may not have this |
| 3 | **5-min auto photo → Telegram receives photo** | 🔴 | **NOT WORKING** — see Alarm analysis below |
| 4 | **`/photo` → Telegram receives photo** | 🔴 | **NOT WORKING** — `CameraService` not started by BotService |
| 5 | **`/status` → Telegram receives status report** | 🔴 | **NOT WORKING** — commands not responding |
| 6 | **`/stop` → Bot stops** | 🔴 | **NOT WORKING** |
| 7 | AI detection → Telegram alert | 🟡 | Model not producing results yet |
| 8 | Debug info visible in UI + Bot | 🟡 | UI shows model "❌ 未加载" — detector not loaded |
| 9 | App in background 30min still running | 🟡 | Foreground service present; not tested |
| 10 | `./gradlew assembleDebug` succeeds | 🟢 | Build clean (1 deprecation warning) |

---

## §10 Known Pitfalls (from spec)

| Pitfall | Spec warning | Reality |
|---|---|---|
| Token change → Bot stops responding | §10 | ✅ Services read token on each operation |
| `/stop` → Bot still running | §10 | ✅ `/stop` calls both service stops |
| `/interval` → not immediate | §10 | ✅ `scheduleAlarm()` called immediately |
| Bot/Camera互相不知道状态 | §10 | ✅ Separation design preserved |
| `cameraServiceRef` never assigned | §10 | ✅ No cross-service references |
| Alert vibration drains battery | §10 | ✅ No vibration code |

---

# 🔴 Critical Bug Analysis

## Bug #3 — Auto Photo Not Working

### Spec requirement
> "定时拍照: AlarmManager 定时触发 → CameraX 拍一张 → 发到 Telegram"

### Code path
```
AlarmReceiver.scheduleAlarm(ctx, intervalMinutes)
    → AlarmManager.setExactAndAllowWhileIdle(triggerAt, pi)
    → [after intervalMinutes]
AlarmReceiver.onReceive(ctx, intent)
    → Config.init(ctx)
    → if Config.enabled → ctx.startForegroundService(CameraService)
    → CameraService.captureAndSend()
    → TelegramBot.sendPhoto()
```

### Root cause analysis

**1. `SCHEDULE_EXACT_ALARM` permission — PARTIALLY FIXED**
- ✅ `android.permission.SCHEDULE_EXACT_ALARM` added to manifest (commit `28efd4f`)
- ⚠️ **On Android 14+ (API 34), `SCHEDULE_EXACT_ALARM` is a restricted permission.**
  The system settings must grant it. Declaring it in the manifest is not enough.
  Apps must use `Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)` to redirect the user.
  Without user grant: `setExactAndAllowWhileIdle()` throws `SecurityException`.

**2. `AlarmReceiver` exported flag**
- `android:exported="false"` — **this is the root cause.**
  A non-exported receiver can ONLY receive broadcasts from the same app.
  `AlarmManager` fires broadcasts **as the system**, not as the app.
  System → non-exported receiver = blocked on Android 12+.
- **Fix:** Change to `android:exported="false"` is actually correct for a private alarm receiver.
  BUT: on Android 12+, if the app is killed, the receiver may not be re-registered.

**3. `PendingIntent.FLAG_MUTABLE` missing**
- `PendingIntent.FLAG_IMMUTABLE` is used. On Android 12+, `setExactAndAllowWhileIdle`
  requires the `PendingIntent` to be **mutable** so the system can fill in the "real time" it fired.
- **Fix:** Change to `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE`

**4. App killed by system**
- Android may kill apps in background aggressively.
- `AlarmReceiver` must be registered **statically** in the manifest (it is).
- `BotService` with `START_STICKY` helps but is not a guarantee.
- After system kills the app, `BootReceiver` restores the schedule on next boot.
- **Gap:** If app is force-stopped by user, alarms are cancelled and NOT restored on boot
  (because `Config.enabled` is still `true` but the user force-stopped it).

### Required fixes
1. `AlarmReceiver` manifest: `android:exported="false"` → keep (correct for private receiver)
2. Add `USE_EXACT_ALARM` permission or redirect to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`
3. Change `PendingIntent.FLAG_IMMUTABLE` → `PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE`
4. Add `Intent.FLAG_RECEIVER_REPLACE_PENDING` to prevent duplicate alarms
5. Add robust logging in `onReceive()` so we can see if it's firing at all

---

## Bug #4 — Telegram Commands Not Working

### Spec requirement
> "BotService: Long Polling 线程独立 / 命令: /start /stop /photo /status"

### Code path
```
BotService.startPolling()
    → TelegramBot.fetchUpdates(token, Config.botOffset)
    → updates.forEach { handleCommand(update.text, update.chatId) }
    → TelegramBot.sendText(token, chatId, response)
```

### Root cause analysis

**1. `NonCancellable` placement — LIKELY ROOT CAUSE**
- `cmdScope.launch(NonCancellable) { handleCommand(...) }` — the `NonCancellable`
  only affects the `handleCommand` coroutine itself, not the `cmdScope`.
- When `onDestroy()` is called (service lifecycle ends), `cmdScope.cancel()` is called.
- `NonCancellable` does NOT protect against explicit `.cancel()` on the parent scope.
- The polling loop (`lifecycleScope.launch`) can also be cancelled when the lifecycle destroys.

**2. `lifecycleScope` usage in `startPolling()` — CONCURRENCY BUG**
- `startPolling()` is called from `onStartCommand()`. The service goes `START_STICKY`.
- BUT: if `onDestroy()` is called before the polling loop finishes processing a batch,
  `lifecycleScope.cancel()` cancels the entire polling loop including any in-flight `handleCommand` calls.
- The `cmdScope` is a separate scope and should survive `lifecycleScope` cancellation,
  BUT `handleCommand` is launched with `NonCancellable` on `cmdScope` — this should work.

**3. `BuildConfig.VERSION_NAME` in `handleCommand()` — POTENTIAL BUILD ERROR**
- `handleCommand()` uses `BuildConfig.VERSION_NAME` directly.
- If `app/build.gradle.kts` does not have `buildConfigField` defined, `BuildConfig` won't have `VERSION_NAME`.
- This would cause a **compile error**, not a runtime issue. Build succeeded, so this is fine.

**4. `cmdScope` not tied to application context — SHOULD BE FINE**
- `CoroutineScope(Dispatchers.IO + SupervisorJob())` is created fresh.
- `SupervisorJob()` means child failures don't crash the scope.
- `cmdScope.cancel()` in `onDestroy()` — this is the key issue.

### Most likely root cause
**`lifecycleScope.launch` in `startPolling()` is a child of the lifecycle — when the service's
lifecycle is destroyed (even briefly), `lifecycleScope` is cancelled, cancelling `pollingJob`.
`cmdScope` is independent but `handleCommand` calls `withContext(Dispatchers.IO)` inside,
and some TelegramBot methods are synchronous (blocking) — they need enough time.

The `NonCancellable` wrapper on `cmdScope.launch(NonCancellable)` SHOULD work,
but the real fix is to make `cmdScope` an **application-scoped** coroutine that never gets
cancelled when the service lifecycle ends.

### Required fixes
1. Replace `cmdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())` with
   `private val cmdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())` initialized in `onCreate()`
2. Change `lifecycleScope.launch { while(...) { ... updates.forEach { cmdScope.launch... } } }` to
   `lifecycleScope.launch { while(...) { ... updates.forEach { withContext(NonCancellable) { handleCommand(...) } } } }`
3. OR: launch `startPolling()` itself in `cmdScope` so it survives lifecycle destruction
4. Add a `Log.d(TAG, "handleCommand: $text")` at the top of `handleCommand()` for debugging

---

## Bug #5 — Model Shows "Not Loaded"

### Spec requirement
> "YOLOv8n 检测照片中的目标 / 🧠 模型：✅ 已加载(1.2s)"

### Code path
```
MainActivity.onCreate()     → CameraService.preloadModel(this)
MainActivity.startAll()      → CameraService.preloadModel(this) [again]
CameraService.processAndSend()
    → if sharedDetector == null → Detector(ctx).load()
    → detector.isReady()
    → detector.detect(bitmap)
    → TelegramBot.sendPhoto(caption with "🤖 模型：✅ 已加载")
```

### Root cause analysis

**1. APK may be old — GitHub Actions takes 2-5 min to build**
- The APK currently on the phone may be from an earlier commit.
- `28efd4f` added `CameraService.preloadModel()` + `SCHEDULE_EXACT_ALARM` permission.
- **Action needed:** Wait for GitHub Actions to finish or build locally.

**2. `yolov8n.tflite` not in APK assets**
- `Detector.load()` copies from `context.assets` to `filesDir`.
- If `yolov8n.tflite` is NOT in `app/src/main/assets/`, the copy step logs:
  `"Model not in filesDir, copying from assets..."` then `copyTo()` throws `FileNotFoundException`.
- `load()` catches the exception → `isReady = false` → model shows "❌ 未加载".
- **Check:** Does `app/src/main/assets/` directory exist? Does it contain `yolov8n.tflite`?

**3. `isModelReady` not updated after cold load in `CameraService.processAndSend()`**
- In `CameraService.processAndSend()`:
  ```kotlin
  if (sharedDetector == null) {
      val d = Detector(this)
      val ok = d.load()
      if (ok) {
          sharedDetector = d
          sharedModelReady = true
          isModelReady = true  // ← static companion var
      }
  }
  ```
- This updates `CameraService.isModelReady` correctly. ✅

**4. `preloadModel()` race condition**
- Called from `MainActivity.onCreate()` AND `MainActivity.startAll()`.
- Both run on the main thread, so no real race. First call loads, second call sees `sharedDetector != null` → no-op. ✅

**5. `refreshDebugPanel()` reads `CameraService.isModelReady`**
- This is the static companion variable. Set by `preloadModel()` or `processAndSend()`.
- If called before either finishes, it shows `false`.
- The 3-second refresh interval should be plenty after app startup.
- **Issue:** `isModelReady` defaults to `false`. If `preloadModel()` fails silently, it stays `false`.

### Most likely root cause
**`yolov8n.tflite` is not in `app/src/main/assets/`** — the model file doesn't exist in the APK.

### Required fixes
1. **Check:** Verify `app/src/main/assets/yolov8n.tflite` exists in the project
2. **Fix:** If missing, download YOLOv8n TFLite from Ultralytics and place in assets
3. **Fix:** Add a `Log.e(TAG, "MODEL FILE MISSING FROM ASSETS")` check in `Detector.load()`
   before trying `context.assets.open()` so the failure is obvious
4. **Fix:** The debug panel in `/status` uses `modelFile.exists()` — this checks `filesDir`,
   not the actual detector readiness. Change to `CameraService.isModelReady`

---

## Bug #6 — `/photo` Not Working (implied from auto photo failure)

### Same root causes as Bug #3 + Bug #4
- Auto photo → AlarmReceiver issue
- `/photo` command → BotService command handling issue
- Both feed into `CameraService`, which has its own issues

---

## Bug #7 — AI Detection Produces No Results

### Spec requirement
> "YOLOv8n 检测照片中的目标（人、车、动物等）"

### Code analysis
```kotlin
// Detector.kt — detect()
val scaled = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
for (y in 0 until inputSize) {
    for (x in 0 until inputSize) {
        val px = scaled.getPixel(x, y)
        inputBuffer.put((px shr 16 and 0xFF).toByte())  // R
        inputBuffer.put((px shr 8  and 0xFF).toByte())  // G
        inputBuffer.put((px and 0xFF).toByte())          // B
    }
}
inputBuffer.rewind()
interpreter.run(inputBuffer, outputBuffer)

// Parse outputs
for (i in 0 until 8400) {
    var maxConf = 0f
    for (c in 0 until 80) {
        val score = outputBuffer[0][4 + c][i]
        if (score > maxConf) { maxConf = score; bestClass = c }
    }
    if (maxConf >= threshold && bestClass < cocoNames.size) {
        results.add(cocoNames[bestClass] to maxConf)
    }
}
```

### Root cause analysis

**1. YOLOv8n output format — LIKELY WRONG**
- The code assumes output shape `[1, 84, 8400]` = `[batch, 84, 8400]`.
- This is the **YOLOv8 raw output BEFORE post-processing**.
- Real YOLOv8n TFLite output is more complex — it usually has:
  - A concat of [1, 64, 8400] (dfl) + [1, 80, 8400] (classes) = [1, 84, 8400] ✅
- The post-processing loop looks reasonable for a basic grid scan.

**2. Input image format — LIKELY WRONG**
- `imageProxyToBitmap()` does:
  ```kotlin
  val buf = image.planes[0].buffer
  val bytes = ByteArray(buf.remaining())
  buf.get(bytes)
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  ```
- `ImageProxy.planes[0]` from CameraX `ImageCapture` is **YUV_420_888**, not JPEG.
- The JPEG compression path (`BitmapFactory.decodeByteArray`) expects a JPEG-encoded
  byte array, but `planes[0]` contains raw YUV luminance data.
- `BitmapFactory.decodeByteArray()` on YUV data produces either:
  - A corrupted/wrong bitmap
  - `null` (if it can't decode)
  - A valid but wrong-format bitmap
- **This is the #1 most likely cause of zero detections.**

**3. RGB vs BGR — POSSIBLE**
- YOLOv8 typically expects BGR input (OpenCV convention).
- The current code writes R, G, B in that order (RGB).
- If the model was exported with BGR expectation, detections will be wrong.
- But this would produce WRONG detections, not ZERO detections.

### Required fixes
1. **Replace `imageProxyToBitmap()` with proper YUV→RGB conversion using `Image.Proxy`
   with `ImageAnalysis` or use `ImageCapture` with `setOnImageCapturedCallback` +
   manual YUV→RGB conversion.
2. Use `yuvToRgb()` utility or `RenderScript` / `ColorSpace` conversion.
3. Add logging: `Log.d(TAG, "Bitmap: ${bitmap.width}x${bitmap.height}, config: ${bitmap.config}")`
4. Add logging after scaling: `Log.d(TAG, "Scaled bitmap: ${scaled.width}x${scaled.height}")`
5. Add logging of output buffer stats: `Log.d(TAG, "Max score in grid: $maxScore")`

---

# Summary Table

| # | Bug | Severity | Root Cause | Fix Owner |
|---|---|---|---|---|
| 1 | Auto photo — `SCHEDULE_EXACT_ALARM` permission not granted | 🔴 Critical | Android 14+ requires user settings approval | Manifest + MainActivity |
| 2 | Auto photo — `FLAG_MUTABLE` missing on PendingIntent | 🔴 Critical | Android 12+ requires mutable PendingIntent for exact alarms | AlarmReceiver |
| 3 | Auto photo — `exported="false"` blocks system broadcasts | 🟡 Medium | `AlarmReceiver` can't receive system-sent broadcasts | Manifest |
| 4 | Telegram commands — `lifecycleScope` cancels command coroutines | 🔴 Critical | `cmdScope` parent cancelled by `lifecycleScope` in `startPolling` | BotService |
| 5 | Model "not loaded" — APK outdated | 🟡 Medium | GitHub Actions takes time; user has old APK | Process (wait for build) |
| 6 | Model "not loaded" — model file missing from assets | 🔴 Critical | `yolov8n.tflite` not in `app/src/main/assets/` | Build / assets |
| 7 | AI produces zero detections — YUV→RGB conversion broken | 🔴 Critical | `imageProxyToBitmap()` feeds YUV bytes into `BitmapFactory` (JPEG decoder) | CameraService |
| 8 | Debug panel shows model not loaded — wrong check | 🟡 Medium | `pushStatus()` uses `File.exists()` not `CameraService.isModelReady` | BotService |
| 9 | Camera preview missing | 🟡 Low | Spec §3 not implemented | activity_main.xml |

---

# Priority Fix Order

1. **[Bug #7]** Fix `imageProxyToBitmap()` — use `YUV_420_888` → RGB conversion
2. **[Bug #6]** Add `yolov8n.tflite` to `app/src/main/assets/`
3. **[Bug #4]** Fix `BotService` coroutine scope — use `MainScope()` or global app scope
4. **[Bug #1+2]** Fix `AlarmReceiver` — `FLAG_MUTABLE` + settings redirect
5. **[Bug #5]** After new APK installs, verify model preloading works
6. **[Bug #8]** Fix `pushStatus()` model check → use `CameraService.isModelReady`
7. **[Bug #9]** Add camera preview to `activity_main.xml` (optional, spec violation)
