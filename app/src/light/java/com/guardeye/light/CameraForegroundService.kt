package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
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

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // Set instance HERE — onCreate is guaranteed to have run before any caller
        // sees the instance via CameraForegroundService.instance.
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
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
        val provider = cameraProvider ?: return
        provider.unbindAll()
        backImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(PhotoQuality.LOW_W, PhotoQuality.LOW_H))
            .setJpegQuality(PhotoQuality.jpegQualityFor(PhotoQuality.LOW))
            .build()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, backImageCapture!!)
        ServiceStatus.setBackCameraBound(true)
    }

    // ── Lifecycle await ────────────────────────────────────────────────────
    // Wait for lifecycle to reach RESUMED before calling takePicture().
    // Remove observer on event or timeout to prevent leaks.

    private fun awaitLifecycleResumed(timeoutMs: Long = 2000): Boolean {
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
     * Blocking front-camera capture (transactional: bind→capture→restore back).
     * Waits for camera init if needed.
     * @param quality Photo quality tier (h/m/l/x)
     * @return JPEG byte array, or empty ByteArray on failure.
     */
    fun captureFrontPhoto(quality: String): ByteArray {
        acquireWakeLock()
        val startNs = System.nanoTime()

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

        val (tw, th) = when (quality.lowercase()) {
            PhotoQuality.MEDIUM -> PhotoQuality.MEDIUM_W to PhotoQuality.MEDIUM_H
            PhotoQuality.LOW    -> PhotoQuality.LOW_W    to PhotoQuality.LOW_H
            else               -> PhotoQuality.HIGH_W    to PhotoQuality.HIGH_H
        }
        val jpegQ = PhotoQuality.jpegQualityFor(quality)

        val frontCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(tw, th))
            .setJpegQuality(jpegQ)
            .build()

        return try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, frontCapture)
            ServiceStatus.updateCameraStatus { copy(frontCameraBound = true) }
            val resumed = awaitLifecycleResumed(2000)
            if (!resumed) Log.w(TAG, "captureFrontPhoto: lifecycle not RESUMED within 2s")
            val result = doCapture(frontCapture, jpegQ, startNs)
            bindBackCamera()
            result
        } catch (e: Exception) {
            Log.e(TAG, "captureFrontPhoto failed", e)
            try { bindBackCamera() } catch (_: Exception) {}
            ServiceStatus.recordCaptureResult(false, 0, "Front capture: ${e.message}")
            ByteArray(0)
        }.also {
            ServiceStatus.updateCameraStatus { copy(frontCameraBound = false) }
            scheduleWakeLockRelease()
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

        // Scale to exact target dimensions (toJpeg gave us native resolution from CameraX)
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
