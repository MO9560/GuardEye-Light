package com.guardeye.light

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.guardeye.Config
import com.guardeye.light.ACTION_MANUAL_CAPTURE
import com.guardeye.light.ACTION_REQUEST_BATTERY
import com.guardeye.R
import com.guardeye.databinding.LightActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LightMainActivity — v4 candy-color UI for GuardEye Light.
 */
class LightMainActivity : AppCompatActivity() {

    private lateinit var ui: LightActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var previewEnabled = false
    private var cameraBound = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要相机和通知权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)
        ui = LightActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        checkPermissions()
        loadConfig()
        setupListeners()
        refreshStatus()
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun loadConfig() {
        ui.sliderInterval.value = Config.intervalMinutes.toFloat()
        updateIntervalText(Config.intervalMinutes)
        previewEnabled = false
        applyPreviewToggle()
    }

    private fun setupListeners() {
        // Preview toggle
        ui.btnTogglePreview.setOnClickListener {
            previewEnabled = !previewEnabled
            applyPreviewToggle()
        }

        // Interval slider
        ui.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            Config.intervalMinutes = mins
            updateIntervalText(mins)
        }

        // Start / Stop
        ui.btnStartStop.setOnClickListener {
            saveConfig()

            if (Config.enabled) {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                stopService(Intent(this, LightBotService::class.java))
                stopCameraPreview()
                previewEnabled = false
                applyPreviewToggle()
                refreshStatus()
            } else {
                if (!validateConfig()) return@setOnClickListener
                Config.enabled = true
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                startForegroundService(Intent(this, LightBotService::class.java))
                refreshStatus()
            }
        }

        // Photo
        ui.btnPhoto.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()
            val svc = Intent(this, LightBotService::class.java).apply {
                action = ACTION_MANUAL_CAPTURE
            }
            startForegroundService(svc)
            Toast.makeText(this, "拍照中...", Toast.LENGTH_SHORT).show()
        }

        // Battery hint / battery temp
        ui.textBatteryHint.setOnClickListener {
            val intent = Intent(this, LightBotService::class.java).apply {
                action = ACTION_REQUEST_BATTERY
            }
            startForegroundService(intent)
            Toast.makeText(this, "请选择「不限」或「不优化」", Toast.LENGTH_LONG).show()
        }

        // Settings tab
        ui.tabSettings.setOnClickListener {
            startActivity(Intent(this, LightSettingsActivity::class.java))
        }

        // Help tab
        ui.tabHelp.setOnClickListener {
            Toast.makeText(this, "/start /stop /photo /status /interval N /debug /battery /test",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Apply preview toggle state:
     * - Toggle button background
     * - Preview card content (live gradient vs grey placeholder)
     * - Camera binding
     */
    private fun applyPreviewToggle() {
        if (previewEnabled && Config.enabled) {
            ui.previewPlaceholder.visibility = View.GONE
            ui.liveOverlay.visibility = View.VISIBLE
            ui.btnTogglePreview.setBackgroundResource(R.drawable.preview_toggle_bg_on)
            ui.previewSurface.visibility = View.VISIBLE
            startCameraPreview()
        } else {
            ui.previewPlaceholder.visibility = View.VISIBLE
            ui.liveOverlay.visibility = View.GONE
            ui.btnTogglePreview.setBackgroundResource(R.drawable.preview_toggle_bg)
            ui.previewSurface.visibility = View.GONE
            stopCameraPreview()
        }

        ui.textPreviewHint.text = when {
            !Config.enabled -> "取景区 · 未启动"
            previewEnabled -> "取景区 · 实时"
            else -> "取景区 · 已关闭预览"
        }
    }

    private fun startCameraPreview() {
        if (cameraBound) return
        val surfaceView = ui.previewSurface
        if (surfaceView.holder.surface.isValid) {
            bindCamera(surfaceView.holder.surface)
        } else {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    bindCamera(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    stopCameraPreview()
                }
            })
        }
    }

    private fun bindCamera(surface: android.view.Surface) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider { request ->
                        request.provideSurface(surface, cameraExecutor) { }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    cameraBound = true
                } catch (e: Exception) {
                    cameraBound = false
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            // Surface may not be ready yet
        }
    }

    private fun stopCameraPreview() {
        if (!cameraBound) return
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                cameraBound = false
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            cameraBound = false
        }
    }

    private fun refreshStatus() {
        val running = Config.enabled

        ui.btnStartStop.text = if (running) "停止监控" else "开始监控"
        ui.btnStartStop.backgroundTintList =
            ContextCompat.getColorStateList(this,
                if (running) R.color.candy_btn_stop else R.color.candy_btn_start)

        ui.cardStatus.setBackgroundResource(
            if (running) R.drawable.card_candy_start else R.drawable.card_candy_stop)
        ui.textStatus.text = "●"
        ui.labelStatus.text = if (running) "运行" else "停止"

        ui.textPreviewHint.text = if (running) "取景区 · 已关闭预览" else "取景区 · 未启动"

        val mins = Config.intervalMinutes
        ui.textInterval.text = "${mins}分"
        updateTemp()
    }

    private fun updateTemp() {
        val intent = registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        ui.textTemp.text = if (temp > 0) "${temp / 10}℃" else "--℃"
    }

    private fun validateConfig(): Boolean {
        if (Config.botToken.isBlank() || Config.chatId.isBlank()) {
            Toast.makeText(this, "请先在设置中填写 Token 和 Chat ID", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun saveConfig() {
        Config.intervalMinutes = ui.sliderInterval.value.toInt()
    }

    private fun updateIntervalText(mins: Int) {
        ui.textInterval.text = "${mins}分"
    }

    override fun onResume() {
        super.onResume()
        updateTemp()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
