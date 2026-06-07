package com.guardeye.light

/**
 * In-process real-time service status collector.
 * Written by CameraForegroundService (cameraExecutor thread) and LightBotService (IO/main threads).
 * Read by StatusActivity (main thread via Handler).
 *
 * Lifecycle: Lives in the same process as LightBotService / CameraForegroundService.
 * The object is a singleton — one instance per app process.
 */
object ServiceStatus {

    data class CameraStatus(
        val serviceRunning: Boolean = false,
        val lifecycleState: String = "UNKNOWN",
        val lifecycleTarget: String = "UNKNOWN",
        val cameraProviderReady: Boolean = false,
        val backCameraBound: Boolean = false,
        val frontCameraBound: Boolean = false,
        val frontCaptureInProgress: Boolean = false,
        val wakeLockHeld: Boolean = false,
        val lastCaptureTime: Long = 0L,
        val lastCaptureSuccess: Boolean = true,
        val lastCaptureDurationMs: Long = 0L,
        val lastErrorMessage: String = "",
        val captureCount: Int = 0,
        val failedCaptureCount: Int = 0,
        val executorQueueSize: Int = 0,
    )

    data class BotStatus(
        val pollingActive: Boolean = false,
        val lastPollTime: Long = 0L,
        val lastPollError: String = "",
        val commandCount: Int = 0,
        val monitoringEnabled: Boolean = false,
        val intervalMinutes: Int = 0,
    )

    // Written from any thread — use volatile for safe cross-thread reads of primitives
    @Volatile var cameraStatus = CameraStatus()
        private set

    @Volatile var botStatus = BotStatus()
        private set

    fun updateCameraStatus(update: CameraStatus.() -> CameraStatus) {
        cameraStatus = cameraStatus.update()
    }

    fun updateBotStatus(update: BotStatus.() -> BotStatus) {
        botStatus = botStatus.update()
    }

    // ── Convenience update helpers ──────────────────────────────────────

    fun recordCaptureStart() {
        updateCameraStatus {
            copy(captureCount = captureCount + 1)
        }
    }

    fun recordCaptureResult(success: Boolean, durationMs: Long, error: String = "") {
        updateCameraStatus {
            copy(
                lastCaptureTime = System.currentTimeMillis(),
                lastCaptureSuccess = success,
                lastCaptureDurationMs = durationMs,
                lastErrorMessage = error,
                failedCaptureCount = if (!success) failedCaptureCount + 1 else failedCaptureCount,
                frontCaptureInProgress = false
            )
        }
    }

    fun recordFrontCaptureStart() {
        updateCameraStatus { copy(frontCaptureInProgress = true) }
    }

    fun setLifecycle(state: String, target: String) {
        updateCameraStatus { copy(lifecycleState = state, lifecycleTarget = target) }
    }

    fun setBackCameraBound(bound: Boolean) {
        updateCameraStatus { copy(backCameraBound = bound) }
    }

    fun setWakeLock(held: Boolean) {
        updateCameraStatus { copy(wakeLockHeld = held) }
    }

    fun setProviderReady(ready: Boolean) {
        updateCameraStatus { copy(cameraProviderReady = ready) }
    }

    /** Wraps any Result-producing block and records failure into ServiceStatus.
     *  Used to track errors from all capture paths (APP / 定时 / TEL / 前摄). */
    inline fun <T> withErrorTracking(tag: String, block: () -> Result<T>): Result<T> {
        return try {
            val result = block()
            if (result.isFailure) {
                val err = result.exceptionOrNull()
                recordCaptureResult(
                    success = false,
                    durationMs = 0L,
                    error = "[$tag] ${err?.javaClass?.simpleName}: ${err?.message}"
                )
            }
            result
        } catch (e: Exception) {
            recordCaptureResult(
                success = false,
                durationMs = 0L,
                error = "[$tag] 异常: ${e.javaClass.simpleName} - ${e.message}"
            )
            Result.failure(e)
        }
    }


    fun setBotPolling(active: Boolean) {
        updateBotStatus { copy(pollingActive = active) }
    }

    fun recordCommand() {
        updateBotStatus { copy(commandCount = commandCount + 1) }
    }

    fun setMonitoring(enabled: Boolean, interval: Int) {
        updateBotStatus { copy(monitoringEnabled = enabled, intervalMinutes = interval) }
    }
}
