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
 * Design: stateless, short-lived. Starts -> captures -> sends -> stops.
 *
 * Actions:
 *   ACTION_CAPTURE -> capture a frame, run AI, send via Telegram, stopSelf()
 *   ACTION_STOP    -> clean up and stop
 *
 * Capture source is passed via EXTRA_SOURCE intent extra:
 *   SOURCE_INTERVAL ("interval") -> triggered by AlarmReceiver
 *   SOURCE_MANUAL  ("manual")  -> triggered by /photo command
 */
class CameraService : LifecycleService() {

    companion object {
        const val TAG = "GuardEye.Camera"
        const val ACTION_CAPTURE = "com.guardeye.action.CAPTURE"
        const val ACTION_STOP    = "com.guardeye.action.CAMERA_STOP"
        const val EXTRA_SOURCE   = "extra_source"
        const val SOURCE_INTERVAL = "interval"
        const val SOURCE_MANUAL   = "manual"

        /** Shared detector — cold-start once, reuse across captures */
        private var sharedDetector: Detector? = null
        private var sharedModelReady = false

        /** Pre-warm the model in MainActivity on app start */
        fun preloadModel(ctx: Context) {
            if (sharedDetector == null) {
                val d = Detector(ctx)
                sharedModelReady = d.load()
                sharedDetector = if (sharedModelReady) d else null
                isModelReady = sharedModelReady
                Log.d(TAG, "Model preloaded: $sharedModelReady")
            }
        }

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

        /** Source of last capture: SOURCE_INTERVAL or SOURCE_MANUAL */
        @Volatile var lastSource: String = SOURCE_MANUAL
            private set
    }

    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    /** Capture source: "interval" or "manual" */
    private var captureSource: String = SOURCE_MANUAL

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        captureSource = intent?.getStringExtra(EXTRA_SOURCE) ?: SOURCE_MANUAL
        when (intent?.action) {
            ACTION_CAPTURE -> captureAndSend()
            ACTION_STOP    -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cameraExecutor?.shutdown()
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
                            "❌ Capture failed: ${exception.message}"
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
        if (sharedDetector == null) {
            val d = Detector(this)
            val ok = d.load()
            if (ok) {
                sharedDetector = d
                sharedModelReady = true
                isModelReady = true
            }
        }

        val aiStartMs = System.currentTimeMillis()
        var detections = emptyList<Pair<String, Float>>()
        if (Config.detectionEnabled && sharedDetector?.isReady() == true) {
            detections = sharedDetector!!.detect(bitmap)
        }
        lastAiDurationMs = System.currentTimeMillis() - aiStartMs

        // ── Record capture metadata ──
        val nowMs = System.currentTimeMillis()
        lastCaptureTime = nowMs
        lastSource = captureSource
        if (captureSource == SOURCE_INTERVAL) {
            Config.lastIntervalCaptureTime = nowMs
        } else {
            Config.lastManualCaptureTime = nowMs
        }
        Config.lastCaptureSource = captureSource

        // ── Build caption ──
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val srcLabel   = if (captureSource == SOURCE_INTERVAL) "Auto capture" else "Manual capture"
        val detectionText = if (detections.isEmpty()) {
            "🔍 Detection: no objects"
        } else {
            "🔍 Detected ${detections.size} object(s):\n" +
                detections.take(5).joinToString("\n") { (label, conf) ->
                    "  • $label ${(conf * 100).toInt()}%"
                }
        }
        lastDetectionText = detectionText

        // ── Alert check ──
        val hasAlert = sharedDetector?.hasAlert(detections) == true

        // ── Build debug block ──
        val debugBlock = if (Config.debugMode) """
            ─────────────────────
            📷 Source: $srcLabel
            ⏱ Capture: ${lastCaptureDurationMs}ms
            🧠 AI inference: ${lastAiDurationMs}ms
            📐 Resolution: ${bitmap.width}x${bitmap.height}
            🔋 Battery: ${getBatteryLevel()}%
            🤖 Model: ${if (sharedDetector?.isReady() == true) "✅ Loaded" else "❌ Not loaded"}
        """.trimIndent() else ""

        val alertText = if (hasAlert) "\n⚠️ **Alert: suspicious object detected**" else ""

        val caption = """
            📸 GuardEye Capture Report
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
            TelegramBot.sendText(token, chatId, "📸 Photo send failed: ${it.message}")
        }

        if (hasAlert) {
            TelegramBot.sendText(
                token, chatId,
                "⚠️ **Alert** — ${detections.joinToString(", ") { "${it.first} ${(it.second * 100).toInt()}%" }}"
            )
        }
    }

    // ── Utilities ─────────────────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
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
