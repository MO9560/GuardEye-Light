package com.guardeye

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardeye.Detector
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera + AI capture service.
 *
 * Design: stateless, short-lived. Starts → captures → sends → stops.
 * Does NOT remain running in background.
 *
 * Actions:
 *   ACTION_CAPTURE → capture a frame, run AI, send via Telegram, stopSelf()
 *   ACTION_STOP    → clean up and stop
 */
class CameraService : LifecycleService() {

    companion object {
        const val TAG = "GuardEye.Camera"
        const val ACTION_CAPTURE = "com.guardeye.action.CAPTURE"
        const val ACTION_STOP    = "com.guardeye.action.CAMERA_STOP"

        /** Static instance ref so BotService can get last detection text */
        @Volatile var lastDetectionText: String = ""
            private set

        @Volatile var lastCaptureTime: Long = 0L
            private set

        @Volatile var isModelReady: Boolean = false
            private set

        @Volatile var lastCaptureDurationMs: Long = 0L
            private set

        @Volatile var lastAiDurationMs: Long = 0L
            private set
    }

    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CAPTURE -> captureAndSend()
            ACTION_STOP    -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cameraExecutor?.shutdown()
        detector?.release()
        cameraProvider?.unbindAll()
        Log.d(TAG, "CameraService destroyed")
        super.onDestroy()
    }

    // ── Capture + Send ─────────────────────────────────────────────

    private fun captureAndSend() {
        val prov = cameraProvider
        val cap = imageCapture
        if (prov == null || cap == null) {
            // Init camera first
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                cameraProvider = cameraProviderFuture.get()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                val cameraSelector = if (Config.cameraFacing == "front")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageCapture)
                doCapture()
            }, ContextCompat.getMainExecutor(this))
            return
        }
        doCapture()
    }

    private fun doCapture() {
        val capture = imageCapture ?: return
        capture.takePicture(
            cameraExecutor!!,
            object : ImageCapture.OnImageCapturedCallback() {
                @SuppressLint("UnsafeOptInUsageError")
                override fun onCaptureSuccess(image: ImageProxy) {
                    val startMs = System.currentTimeMillis()
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    lastCaptureDurationMs = System.currentTimeMillis() - startMs

                    lifecycleScope.launch(Dispatchers.IO) {
                        processAndSend(bitmap)
                        withContext(Dispatchers.Main) { stopSelf() }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed: ${exception.message}", exception)
                    lifecycleScope.launch(Dispatchers.IO) {
                        TelegramBot.sendText(
                            Config.botToken, Config.chatId,
                            "❌ 拍照失败：${exception.message}"
                        )
                        withContext(Dispatchers.Main) { stopSelf() }
                    }
                }
            }
        )
    }

    private suspend fun processAndSend(bitmap: Bitmap) {
        val token = Config.botToken
        val chatId = Config.chatId
        if (token.isBlank() || chatId.isBlank()) return

        // ── AI Detection ──
        if (detector == null) {
            detector = Detector(this)
            detector!!.load()
            isModelReady = detector!!.isReady()
        }

        val aiStartMs = System.currentTimeMillis()
        var detections = emptyList<Pair<String, Float>>()
        if (Config.detectionEnabled && detector?.isReady() == true) {
            detections = detector!!.detect(bitmap)
        }
        lastAiDurationMs = System.currentTimeMillis() - aiStartMs

        // ── Build caption ──
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val detectionText = if (detections.isEmpty()) {
            "🔍 检测：无目标"
        } else {
            "🔍 检测到 ${detections.size} 个目标：\n" +
                detections.take(5).joinToString("\n") { (label, conf) ->
                    "  • $label ${(conf * 100).toInt()}%"
                }
        }
        lastDetectionText = detectionText
        lastCaptureTime = System.currentTimeMillis()

        // ── Alert check ──
        val hasAlert = detector?.hasAlert(detections) == true

        // ── Build debug block ──
        val debugBlock = if (Config.debugMode) """
            ─────────────────────
            ⏱ 拍照：${lastCaptureDurationMs}ms
            🧠 AI推理：${lastAiDurationMs}ms
            📐 分辨率：${bitmap.width}×${bitmap.height}
            🔋 电量：${getBatteryLevel()}%
            🤖 模型：${if (detector?.isReady() == true) "✅ 已加载" else "❌ 未加载"}
        """.trimIndent() else ""

        val alertText = if (hasAlert) "\n⚠️ **告警：可疑目标已检测**" else ""

        val caption = """
            📸 GuardEye 拍照报告
            ─────────────────────
            🕐 $timestamp
            $detectionText
            $alertText
            $debugBlock
        """.trimIndent()

        // ── Send photo ──
        val bytes = bitmapToJpeg(bitmap, 80)
        val photoResult = TelegramBot.sendPhoto(token, chatId, bytes, caption)
        photoResult.onFailure {
            Log.e(TAG, "sendPhoto failed: ${it.message}")
            TelegramBot.sendText(token, chatId, "📸 照片发送失败：${it.message}")
        }

        if (hasAlert) {
            TelegramBot.sendText(
                token, chatId,
                "⚠️ **告警推送** — ${detections.joinToString(", ") { "${it.first} ${(it.second * 100).toInt()}%" }}"
            )
        }
    }

    // ── Utilities ─────────────────────────────────────────────────

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return baos.toByteArray()
    }

    private fun getBatteryLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 0
    }
}
