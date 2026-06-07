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
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// ImageProxy.toJpeg() — convert YUV_420_888 → RGB Bitmap → JPEG in memory
// CameraX ImageCapture.OnImageCapturedCallback gives YUV_420_888 format.
// Quality 70 → single photo ~15–30KB, good for recognition + low bandwidth.
// ─────────────────────────────────────────────────────────────────────────────
private fun ImageProxy.toJpeg(quality: Int = 70): ByteArray {
    val w = width
    val h = height

    // Extract YUV planes (YUV_420_888: U/V are 2×2 subsampled)
    val yBuf = planes[0].buffer
    val uBuf = planes[1].buffer
    val vBuf = planes[2].buffer
    val yStride = planes[0].rowStride
    val uvStride = planes[1].rowStride  // same as planes[2]

    val yBytes = ByteArray(yBuf.remaining()); yBuf.get(yBytes)
    val uBytes = ByteArray(uBuf.remaining()); uBuf.get(uBytes)
    val vBytes = ByteArray(vBuf.remaining()); vBuf.get(vBytes)
    close()

    val rgb = IntArray(w * h)
    var yi = 0
    for (row in 0 until h) {
        for (col in 0 until w) {
            val y = yBytes[row * yStride + col].toInt() and 0xFF
            val uvI = (row / 2) * uvStride + (col / 2) * 2
            val u = uBytes.getOrElse(uvI) { 0 }.toInt() and 0xFF
            val v = vBytes.getOrElse(uvI + 1) { 0 }.toInt() and 0xFF

            // BT.601 YUV → RGB conversion
            val r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
            val g = (y - 0.344 * (u - 128) - 0.714 * (v - 128)).toInt().coerceIn(0, 255)
            val b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
            rgb[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(rgb, 0, w, 0, 0, w, h)

    val baos = ByteArrayOutputStream(w * h / 4)
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    bitmap.recycle()
    return baos.toByteArray()
}

// ─────────────────────────────────────────────────────────────────────────────
// CameraForegroundService — pure camera controller (v2.0)
// ─────────────────────────────────────────────────────────────────────────────
class CameraForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "CameraForegroundSvc"
        var instance: CameraForegroundService? = null
            private set
    }

    init { instance = this }

    // ── CameraX ──────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var backImageCapture: ImageCapture? = null

    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // ── WakeLock — synchronized, held only during capture ─────────────────────
    private var wakeLock: PowerManager.WakeLock? = null
    private val releaseHandler = Handler(Looper.getMainLooper())
    private var releaseRunnable: Runnable? = null
    private val wakeLockTimeout = 10_000L  // 10 s

    // ── Foreground notification ─────────────────────────────────────────────
    private val CHANNEL_ID = "guardeye_camera_svc"
    private val NOTIF_ID   = 2002

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        initializeCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService lifecycle is already >= STARTED here.
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        releaseWakeLockImmediate()
        releaseRunnable?.let { releaseHandler.removeCallbacks(it) }
        cameraExecutor.shutdownNow()
        cameraProvider?.shutdown()
        instance = null
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

    // ── WakeLock (synchronized) ──────────────────────────────────────────────

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
            releaseHandler.removeCallbacksAndMessages(null)
        }
    }

    // ── Camera initialization ────────────────────────────────────────────────

    private fun initializeCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindBackCamera()
                Log.d(TAG, "Camera initialized — back camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
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
    }

    // ── Lifecycle await ─────────────────────────────────────────────────────
    // Wait for lifecycle to reach RESUMED before calling takePicture().
    // Remove observer after event fires or timeout to prevent leaks.

    private fun awaitLifecycleResumed(timeoutMs: Long = 2000): Boolean {
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
            if (!ok) lifecycle.removeObserver(observer)  // timeout cleanup
            ok
        } catch (e: Exception) {
            lifecycle.removeObserver(observer)
            false
        }
    }

    // ── Public capture API ──────────────────────────────────────────────────

    fun isReady(): Boolean = cameraProvider != null && backImageCapture != null

    /**
     * Capture with the back camera.
     * @return JPEG byte array (quality 70), or empty ByteArray on failure.
     */
    fun captureBackPhoto(quality: String): ByteArray {
        acquireWakeLock()
        val ic = backImageCapture
        if (ic == null || cameraProvider == null) {
            Log.e(TAG, "captureBackPhoto: not ready")
            scheduleWakeLockRelease()
            return ByteArray(0)
        }
        // LifecycleService lifecycle is already >= STARTED — capture immediately
        return doCapture(ic, PhotoQuality.jpegQualityFor(quality)).also {
            scheduleWakeLockRelease()
        }
    }

    /**
     * Capture with the front camera (transactional).
     * bind(front) → await RESUMED → capture → restore(back) → return.
     * @return JPEG byte array, or empty ByteArray on failure.
     */
    fun captureFrontPhoto(quality: String): ByteArray {
        acquireWakeLock()
        val provider = cameraProvider
        if (provider == null) {
            Log.e(TAG, "captureFrontPhoto: camera not ready")
            scheduleWakeLockRelease()
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
            awaitLifecycleResumed(2000)
            val result = doCapture(frontCapture, jpegQ)
            bindBackCamera()
            result
        } catch (e: Exception) {
            Log.e(TAG, "captureFrontPhoto failed", e)
            try { bindBackCamera() } catch (_: Exception) {}
            ByteArray(0)
        }.also { scheduleWakeLockRelease() }
    }

    // ── Core capture (runs on cameraExecutor) ───────────────────────────────
    // Uses YUV→JPEG conversion for minimal bandwidth (quality 70 ≈ 15–30KB/photo).

    private fun doCapture(imgCapture: ImageCapture, quality: Int = 70): ByteArray {
        val latch = CountDownLatch(1)
        var capturedBytes: ByteArray? = null
        var errorMsg: String? = null

        imgCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        capturedBytes = image.toJpeg(quality)
                    } catch (e: Exception) {
                        errorMsg = e.message
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    errorMsg = exception.message
                    Log.e(TAG, "takePicture error: ${exception.imageCaptureError}", exception)
                    latch.countDown()
                }
            }
        )

        if (!latch.await(15, TimeUnit.SECONDS)) {
            Log.e(TAG, "doCapture: timeout after 15s")
            return ByteArray(0)
        }

        if (errorMsg != null) {
            Log.e(TAG, "doCapture error: $errorMsg")
            return ByteArray(0)
        }

        return capturedBytes ?: ByteArray(0)
    }
}
