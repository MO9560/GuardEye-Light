package com.guardeye.light

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.guardeye.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纯文本 emoji 版状态页 — 每秒刷新
 * 打开方式: LightMainActivity 点击状态 tab
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.status_activity)
        statusText = findViewById(R.id.status_text)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun refresh() {
        val s = ServiceStatus.cameraStatus
        val b = ServiceStatus.botStatus
        val now = timeFormat.format(Date())

        val sb = StringBuilder()

        sb.appendLine("═══ GuardEye Light 状态 ═══")
        sb.appendLine()
        sb.appendLine("📱 Telegram Bot:")
        sb.appendLine(" · Polling: ${if (b.pollingActive) "● ACTIVE" else "○ stopped"}")
        sb.appendLine(" · Commands: ${b.commandCount}")
        sb.appendLine()
        sb.appendLine("📸 相机服务:")
        sb.appendLine(" · 服务: ${if (s.serviceRunning) "✅ 运行中" else "❌ 已停止"}")
        sb.appendLine(" · Lifecycle: ${s.lifecycleState}")
        sb.appendLine(" · CameraProvider: ${if (s.cameraProviderReady) "✅ ready" else "❌ NOT ready"}")
        sb.appendLine(" · 后镜头: ${if (s.backCameraBound) "✅ 已绑定" else "❌ 未绑定"}")
        sb.appendLine(" · 前镜头: ${if (s.frontCameraBound) "✅ 已绑定" else "❌ 未绑定"}")
        sb.appendLine()
        sb.appendLine("🔋 WakeLock: ${if (s.wakeLockHeld) "⚡ 持有中" else "💤 已释放"}")
        sb.appendLine("🔄 前摄拍照: ${if (s.frontCaptureInProgress) "进行中..." else "idle"}")
        sb.appendLine()
        sb.appendLine("📊 拍照统计:")
        sb.appendLine(" · 总次数: ${s.captureCount}")
        sb.appendLine(" · 失败次数: ${s.failedCaptureCount}")
        sb.appendLine(" · 上次拍照: ${formatTime(s.lastCaptureTime)}")
        sb.appendLine(" · 耗时: ${s.lastCaptureDurationMs}ms")
        sb.appendLine(" · 上次结果: ${if (s.lastCaptureSuccess) "✅" else "❌"}")

        if (s.lastErrorMessage.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("⚠️ 最后错误:")
            sb.appendLine(" ${s.lastErrorMessage}")
        }

        sb.appendLine()
        sb.appendLine("⏰ 更新时间: $now")

        statusText.text = sb.toString()
    }

    private fun formatTime(millis: Long): String {
        if (millis == 0L) return "暂无"
        return timeFormat.format(Date(millis))
    }
}
