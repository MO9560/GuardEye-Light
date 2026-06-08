package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.guardeye.Config
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// ImageProxy → Bitmap → JPEG
// CameraX outputs YUV_420_888. Quality 70 ≈ 15–30 KB/photo — good for
// recognition + bandwidth.  We do YUV→RGB→Bitmap→JPEG in-process (no native lib).
// ─────────────────────────────────────────────────────────────────────────────
private fun ImageProxy.toBitmap(): Bitmap {
    val w = width
    val h = height
    val yBuf = planes[0].buffer
    val uBuf = planes[1].buffer
    val vBuf = planes[2].buffer
    val yStride = planes[0].rowStride
    val uvStride = planes[1].rowStride

    val yBytes = ByteArray(yBuf.remaining()); yBuf.get(yBytes)
    val uBytes = ByteArray(uBuf.remaining()); uBuf.get(uBytes)
    val vBytes = ByteArray(vBuf.remaining()); vBuf.get(vBytes)
    close()

    val rgb = IntArray(w * h)
    for (row in 0 until h) {
        for (col in 0 until w) {
            val y = yBytes[row * yStride + col].toInt() and 0xFF
            val uvI = (row / 2) * uvStride + (col / 2) * 2
            val u = uBytes.getOrElse(uvI) { 0 }.toInt() and 0xFF
            val v = vBytes.getOrElse(uvI + 1) { 0 }.toInt() and 0xFF
            val r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
            val g = (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)
            val b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
            rgb[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(rgb, 0, w, 0, 0, w, h) }
}

// ─────────────────────────────────────────────────────────────────────────────
// CameraForegroundService — pure camera controller (v2.0)
// Responsibilities:
//   - Own ProcessCameraProvider + lifecycle
//   - Bind / unbind use cases
//   - Expose blocking capture methods (called from LightBotService IO threads)
// ─────────────────────────────────────────────────────────────────────────────
class CameraForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "CameraForegroundSvc"
        // Set in onCreate (not init{}) so onCreate is guaranteed to have run
        // before any external code sees the instance.
        var instance: CameraForegroundService? = null
            private set
    }

    // ── CameraX ──────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var backImageCapture: ImageCapture? = null

    // Single dedicated thread for all camera I/O — avoids blocking the IO pool.
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ── Lifecycle monitoring ─────────────────────────────────────────────────
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        ServiceStatus.setLifecycle(lifecycle.currentState.name, event.name)
        Log.d(TAG, "Lifecycle: ${event.name} → ${lifecycle.currentState.name}")
    }

    // ── WakeLock ────────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null
    private val releaseHandler = Handler(Looper.getMainLooper())
    private var releaseRunnable: Runnable? = null
    private val wakeLockTimeout = 15_000L  // 15 s

    // ── Notification ───────────────────────────────────────────────────────
    private val CHANNEL_ID = "guardeye_camera_svc"
    private val NOTIF_ID   = 2002

    // ── Front camera retry constants (v2.0) ────────────────────────────────
    // Total attempts = FRONT_MAX_ATTEMPTS (index 0, 1, 2 = 3 attempts)
    private companion object FrontRetry {
        const val MAX_ATTEMPTS      = 3
        const val INITIAL_DELAY_MS  = 500L   // attempt 1: 500ms, attempt 2: 1000ms, attempt 3: 2000ms
        const val MAX_DELAY_MS      = 3000L  // cap
        const val LIFECYCLE_TIMEOUT = 15_000L // Doze can delay lifecycle by several seconds

        /** Exponential backoff: delay = INITIAL_DELAY_MS * 2^(attempt-1), capped at MAX_DELAY_MS */
        fun backoffDelay(attemptIndex: Int): Long {
            // attemptIndex is 1-based: 1 → 500ms, 2 → 1000ms, 3 → 2000ms
            return (INITIAL_DELAY_MS * (1L shl (attemptIndex - 1))).coerceAtMost(MAX_DELAY_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Config.init(this)  // needed for onTaskRemoved() to read Config.enabled
        instance = this
        ServiceStatus.updateCameraStatus { copy(serviceRunning = true) }
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        lifecycle.addObserver(lifecycleObserver)
        initializeCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService lifecycle is already >= STARTED here.
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved — attempting to survive app swipe")
        // v2.0: Restart the service immediately to survive app swipe from recents.
        // This is a best-effort measure; some ROMs (MIUI, HarmonyOS) kill the
        // process directly without calling onTaskRemoved. Those require battery
        // whitelist (OPT-7) which is handled separately in LightBotService.
        if (Config.enabled) {
            val restartIntent = Intent(this, LightBotService::class.java).apply {
                action = ACTION_CAPTURE
            }
            startService(restartIntent)
            Log.d(TAG, "onTaskRemoved: service restarted to survive app swipe")
        }
    }

    override fun onDestroy() {
        lifecycle.removeObserver(lifecycleObserver)
        releaseHandler.removeCallbacksAndMessages(null)
        releaseWakeLockImmediate()
        cameraExecutor.shutdownNow()
        instance = null
        ServiceStatus.updateCameraStatus {
            copy(serviceRunning = false, backCameraBound = false, frontCameraBound = false)
        }
        cameraProvider?.shutdown()
        cameraProvider = null
        super.onDestroy()
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "GuardEye Camera", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "Camera service for GuardEye Light"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("GuardEye Camera")
        .setContentText("Camera service active")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)                              // Cannot be swiped away
        .setPriority(NotificationCompat.PRIORITY_LOW)  // Low disruption but persistent
        .build()

    // ── WakeLock ──────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        synchronized(this) {
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuardEye:CameraSvc")
                }
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(wakeLockTimeout)
            }
            ServiceStatus.setWakeLock(wakeLock?.isHeld == true)
            releaseHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun scheduleWakeLockRelease() {
        releaseHandler.removeCallbacksAndMessages(null)
        releaseRunnable = Runnable { releaseWakeLockImmediate() }
        releaseHandler.postDelayed(releaseRunnable!!, wakeLockTimeout)
    }

    private fun releaseWakeLockImmediate() {
        synchronized(this) {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            ServiceStatus.setWakeLock(false)
            releaseHandler.removeCallbacksAndMessages(null)
        }
    }

    // ── Camera initialization ────────────────────────────────────────────────

    private fun initializeCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                ServiceStatus.setProviderReady(true)
                bindBackCamera()
                Log.d(TAG, "Camera initialized — back camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                ServiceStatus.recordCaptureResult(
                    success = false, durationMs = 0,
                    error = "Camera init failed: ${e.message}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindBackCamera() {
        val provider = cameraProvider ?: run {
            Log.w(TAG, "bindBackCamera: provider is null")
            return
        }
        try {
            provider.unbindAll()
            backImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1920, 1080))
                .setJpegQuality(95)
                .build()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, backImageCapture!!)
            ServiceStatus.setBackCameraBound(true)
            Log.d(TAG, "bindBackCamera: OK — back camera bound at 1920x1080")
        } catch (e: Exception) {
            Log.e(TAG, "bindBackCamera failed", e)
            ServiceStatus.setBackCameraBound(false)
            backImageCapture = null
        }
    }

    // ── Lifecycle await ────────────────────────────────────────────────────
    // Wait for lifecycle to reach RESUMED before calling takePicture().
    // Remove observer on event or timeout to prevent leaks.

    private fun awaitLifecycleResumed(timeoutMs: Long): Boolean {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return true
        val latch = CountDownLatch(1)
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                lifecycle.removeObserver(this)
                latch.countDown()
            }
        }
        lifecycle.addObserver(observer)
        return try {
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) lifecycle.removeObserver(observer)
            ok
        } catch (_: Exception) {
            lifecycle.removeObserver(observer)
            false
        }
    }

    // ── Public capture API ─────────────────────────────────────────────────

    fun isReady(): Boolean = cameraProvider != null && backImageCapture != null

    /**
     * Blocking back-camera capture.
     * Waits up to 5 s for async camera init, then captures and returns JPEG bytes.
     * @param quality Photo quality tier (h/m/l/x)
     * @return JPEG byte array, or empty ByteArray on failure.
     */
    fun captureBackPhoto(quality: String): ByteArray {
        acquireWakeLock()
        val startNs = System.nanoTime()

        if (!isReady()) {
            Log.d(TAG, "captureBackPhoto: waiting for camera init...")
            var waited = 0L
            while (!isReady() && waited < 5_000) {
                Thread.sleep(200)
                waited += 200
            }
            if (!isReady()) {
                Log.e(TAG, "captureBackPhoto: camera not ready after 5s wait")
                scheduleWakeLockRelease()
                ServiceStatus.recordCaptureResult(false, 0, "Camera not ready")
                return ByteArray(0)
            }
        }

        val ic = backImageCapture!!
        return doCapture(ic, PhotoQuality.jpegQualityFor(quality), startNs).also {
            scheduleWakeLockRelease()
        }
    }

    /**
     * Blocking front-camera capture with retry (v2.0).
     *
     * Retry strategy:
     * - Up to 3 attempts (MAX_ATTEMPTS = 3, indexed 0..2).
     * - Exponential backoff between attempts: 500ms, 1000ms, 2000ms.
     * - On each retry, re-checks cameraProvider state.
     * - Falls back to back camera if all 3 attempts fail.
     *
     * @param quality Photo quality tier (h/m/l/x)
     * @return JPEG byte array, or empty ByteArray on failure.
     */
    fun captureFrontPhoto(quality: String): ByteArray {
        acquireWakeLock()
        val startNs = System.nanoTime()

        // Wait for provider (max 5s once at the beginning)
        var waited = 0L
        while (cameraProvider == null && waited < 5_000) {
            Thread.sleep(200)
            waited += 200
        }
        val provider = cameraProvider
        if (provider == null) {
            Log.e(TAG, "captureFrontPhoto: provider not ready after 5s wait")
            scheduleWakeLockRelease()
            ServiceStatus.recordCaptureResult(false, 0, "Provider not ready")
            return ByteArray(0)
        }

        // ── Retry loop: attempt 0, 1, 2 (total 3 attempts) ──
        for (attempt in 0 until MAX_ATTEMPTS) {
            // Re-check provider state before each retry attempt.
            // Provider can become null if the service is destroyed between attempts.
            if (attempt > 0) {
                if (cameraProvider == null) {
                    Log.w(TAG, "captureFrontPhoto: provider gone at retry $attempt — breaking")
                    break
                }
                val delayMs = backoffDelay(attempt)
                Log.d(TAG, "captureFrontPhoto: retry $attempt in ${delayMs}ms")
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            val result = tryCaptureFrontOnce(provider, quality, startNs)
            if (result.isNotEmpty()) {
                scheduleWakeLockRelease()
                return result
            }
            Log.w(TAG, "captureFrontPhoto: attempt $attempt failed")
        }

        // All retries exhausted — fallback to back camera
        Log.w(TAG, "All $MAX_ATTEMPTS front attempts failed — fallback to back camera")
        try {
            bindBackCamera()
        } catch (_: Exception) {
            Log.e(TAG, "Fallback back camera also failed", Exception("Fallback bindBackCamera failed"))
        }
        ServiceStatus.recordCaptureResult(false, 0, "Front failed after $MAX_ATTEMPTS retries")
        scheduleWakeLockRelease()
        return ByteArray(0)
    }

    /**
     * Single front camera capture attempt (v2.0).
     *
     * Selective unbind: only unbinds the back ImageCapture, keeping the
     * lifecycle binding intact. After capture, rebinds the back camera.
     *
     * @param provider ProcessCameraProvider (captured before retry loop)
     * @param quality Photo quality tier
     * @param startNs NanoTime at capture start (for duration measurement)
     */
    private fun tryCaptureFrontOnce(
        provider: ProcessCameraProvider,
        quality: String,
        startNs: Long
    ): ByteArray {
        val (tw, th) = when (quality.lowercase()) {
            PhotoQuality.MEDIUM -> PhotoQuality.MEDIUM_W to PhotoQuality.MEDIUM_H
            PhotoQuality.LOW    -> PhotoQuality.LOW_W    to PhotoQuality.LOW_H
            else               -> PhotoQuality.HIGH_W   to PhotoQuality.HIGH_H
        }
        val jpegQ = PhotoQuality.jpegQualityFor(quality)

        val frontCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(tw, th))
            .setJpegQuality(jpegQ)
            .build()

        return try {
            // Selective unbind: only unbind back ImageCapture, keep lifecycle binding intact
            backImageCapture?.let {
                provider.unbind(it)
                backImageCapture = null
                ServiceStatus.setBackCameraBound(false)
            }

            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, frontCapture)
            ServiceStatus.updateCameraStatus { copy(frontCameraBound = true) }

            // v2.0: extend from 3000ms to 15000ms to handle Doze mode delays
            val resumed = awaitLifecycleResumed(LIFECYCLE_TIMEOUT)
            if (!resumed) {
                Log.w(TAG, "captureFrontPhoto: lifecycle not RESUMED within ${LIFECYCLE_TIMEOUT}ms")
            }

            val result = doCapture(frontCapture, jpegQ, startNs)

            // Unbind front and restore back camera
            provider.unbind(frontCapture)
            bindBackCamera()
            result
        } catch (e: Exception) {
            Log.e(TAG, "tryCaptureFrontOnce failed: ${e.message}", e)
            ByteArray(0)
        }.also {
            ServiceStatus.updateCameraStatus { copy(frontCameraBound = false) }
        }
    }

    // ── Core capture (runs on cameraExecutor) ──────────────────────────────

    private fun doCapture(imgCapture: ImageCapture, quality: Int, startNs: Long): ByteArray {
        val latch = CountDownLatch(1)
        var capturedBitmap: Bitmap? = null
        var errorMsg: String? = null

        imgCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        capturedBitmap = image.toBitmap()
                    } catch (e: Exception) {
                        errorMsg = e.message
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    errorMsg = "${exception.imageCaptureError}: ${exception.message}"
                    Log.e(TAG, "takePicture error: $errorMsg", exception)
                    latch.countDown()
                }
            }
        )

        if (!latch.await(15, TimeUnit.SECONDS)) {
            Log.e(TAG, "doCapture: timeout after 15s")
            ServiceStatus.recordCaptureResult(false, 0, "Capture timeout")
            return ByteArray(0)
        }

        val err = errorMsg
        if (err != null) {
            Log.e(TAG, "doCapture error: $err")
            ServiceStatus.recordCaptureResult(false, 0, err)
            return ByteArray(0)
        }

        val bitmap = capturedBitmap ?: run {
            ServiceStatus.recordCaptureResult(false, 0, "No bitmap")
            return ByteArray(0)
        }

        // Scale to exact target dimensions
        val (maxW, maxH) = when (quality) {
            PhotoQuality.jpegQualityFor(PhotoQuality.HIGH)   -> PhotoQuality.HIGH_W   to PhotoQuality.HIGH_H
            PhotoQuality.jpegQualityFor(PhotoQuality.MEDIUM) -> PhotoQuality.MEDIUM_W to PhotoQuality.MEDIUM_H
            else                                            -> PhotoQuality.LOW_W    to PhotoQuality.LOW_H
        }

        val scaled = if (bitmap.width != maxW || bitmap.height != maxH) {
            Bitmap.createScaledBitmap(bitmap, maxW, maxH, true).also { bitmap.recycle() }
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream(maxW * maxH / 4)
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        scaled.recycle()

        val durationMs = (System.nanoTime() - startNs) / 1_000_000
        ServiceStatus.recordCaptureResult(success = true, durationMs = durationMs)
        return out.toByteArray()
    }
}
