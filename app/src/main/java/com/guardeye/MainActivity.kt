package com.guardeye

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var sliderInterval: SeekBar
    private lateinit var tvInterval: TextView
    private lateinit var switchDetection: Switch
    private lateinit var switchDebug: Switch
    private lateinit var btnSave: Button
    private lateinit var btnStart: Button
    private lateinit var tvBotStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvDebug: TextView

    private val handler = Handler(Looper.getMainLooper())

    // Refresh debug panel every 3 seconds
    private val refreshDebugRunnable = object : Runnable {
        override fun run() {
            refreshDebugPanel()
            handler.postDelayed(this, 3_000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            requestBatteryOpt()
        } else {
            Toast.makeText(this, "需要相机和网络权限", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Config.init(this)

        // Pre-warm model early so it's ready before first photo
        CameraService.preloadModel(this)

        initViews()
        loadConfig()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshDebugRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshDebugRunnable)
    }

    // ── Init ────────────────────────────────────────────────────

    private fun initViews() {
        etBotToken      = findViewById(R.id.etBotToken)
        etChatId        = findViewById(R.id.etChatId)
        sliderInterval  = findViewById(R.id.sliderInterval)
        tvInterval      = findViewById(R.id.tvInterval)
        switchDetection = findViewById(R.id.switchDetection)
        switchDebug     = findViewById(R.id.switchDebug)
        btnSave         = findViewById(R.id.btnSave)
        btnStart        = findViewById(R.id.btnStart)
        tvBotStatus     = findViewById(R.id.tvBotStatus)
        tvVersion       = findViewById(R.id.tvVersion)
        tvDebug         = findViewById(R.id.tvDebug)
        tvVersion.text  = "v${BuildConfig.VERSION_NAME}"
    }

    private fun loadConfig() {
        etBotToken.setText(Config.botToken)
        etChatId.setText(Config.chatId)
        sliderInterval.progress = (Config.intervalMinutes - 1).coerceIn(0, 9)
        tvInterval.text = "${Config.intervalMinutes} 分钟"
        switchDetection.isChecked = Config.detectionEnabled
        switchDebug.isChecked = Config.debugMode
        refreshBotStatusUI()
    }

    private fun setupListeners() {
        // Interval slider
        sliderInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val mins = (progress + 1).coerceIn(1, 10)
                tvInterval.text = "$mins 分钟"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Save button
        btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "✅ 配置已保存", Toast.LENGTH_SHORT).show()
        }

        // Start button
        btnStart.setOnClickListener {
            saveConfig()
            if (!Config.isConfigured) {
                Toast.makeText(this, "请填写 Bot Token 和 Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionsAndStart()
        }
    }

    // ── Config ──────────────────────────────────────────────────

    private fun saveConfig() {
        Config.botToken        = etBotToken.text.toString().trim()
        Config.chatId         = etChatId.text.toString().trim()
        Config.intervalMinutes = sliderInterval.progress + 1
        Config.detectionEnabled = switchDetection.isChecked
        Config.debugMode      = switchDebug.isChecked
    }

    // ── Permissions & Start/Stop ─────────────────────────────────

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            requestBatteryOpt()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestBatteryOpt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
        startAll()
    }

    private fun startAll() {
        Config.enabled = true
        Config.botToken = etBotToken.text.toString().trim()
        Config.chatId = etChatId.text.toString().trim()
        Config.intervalMinutes = sliderInterval.progress + 1

        // 0. Pre-warm YOLO model so first photo is fast
        CameraService.preloadModel(this)

        // 1. Start BotService
        val botIntent = Intent(this, BotService::class.java).apply {
            action = BotService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(botIntent)
        } else {
            startService(botIntent)
        }

        // 2. Start BotService command: tell bot I'm starting
        Handler(Looper.getMainLooper()).postDelayed({
            TelegramBot.sendText(Config.botToken, Config.chatId,
                "🚀 *GuardEye 已启动*\n间隔：${Config.intervalMinutes}分钟\n调试：${if (Config.debugMode) "开启" else "关闭"}")
        }, 2000)

        // 3. Take first photo immediately
        Handler(Looper.getMainLooper()).postDelayed({
            val camIntent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(camIntent)
            } else {
                startService(camIntent)
            }
        }, 3000)

        // 4. Schedule periodic alarm
        AlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)

        refreshBotStatusUI()
        Toast.makeText(this, "🚀 GuardEye 已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopAll() {
        Config.enabled = false

        // Stop alarm
        AlarmReceiver.cancelAlarm(this)

        // Stop BotService
        val botIntent = Intent(this, BotService::class.java).apply {
            action = BotService.ACTION_STOP
        }
        startService(botIntent)

        // Stop CameraService
        val camIntent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(camIntent)

        TelegramBot.sendText(Config.botToken, Config.chatId, "⏸ *GuardEye 已停止*")

        refreshBotStatusUI()
        Toast.makeText(this, "⏹ GuardEye 已停止", Toast.LENGTH_SHORT).show()
    }

    // ── UI Refresh ──────────────────────────────────────────────

    private fun refreshBotStatusUI() {
        val tokenOk = Config.botToken.isNotBlank()
        val chatOk = Config.chatId.isNotBlank()
        val running = Config.enabled

        tvBotStatus.apply {
            text = when {
                !tokenOk || !chatOk -> "⚠️ Token 或 Chat ID 未填写"
                running -> "✅ 监控中"
                else -> "⏹ 已停止"
            }
            setTextColor(ContextCompat.getColor(this@MainActivity,
                when {
                    !tokenOk || !chatOk -> R.color.red
                    running -> R.color.green
                    else -> R.color.text_secondary
                }
            ))
        }
    }

    private fun refreshDebugPanel() {
        val token = Config.botToken
        val chatId = Config.chatId

        // Memory
        val rt = Runtime.getRuntime()
        val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val maxMB  = rt.maxMemory() / 1024 / 1024
        val freeMB = (rt.freeMemory()) / 1024 / 1024

        // Model
        val modelFile = File(filesDir, "yolov8n.tflite")
        val modelStatus = if (modelFile.exists()) "✅ 已加载" else "❌ 未加载(${filesDir.absolutePath})"

        // Battery
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 0

        // Camera
        val cameraStatus = if (CameraService.isModelReady) "✅ 就绪" else "⏳ 初始化中"

        // Last capture
        val lastCap = if (CameraService.lastCaptureTime > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(CameraService.lastCaptureTime))
        } else "—"

        val detectionText = CameraService.lastDetectionText.ifBlank { "无" }

        val text = buildString {
            appendLine("🤖 Bot：${if (token.isNotBlank()) "✅ Token OK" else "❌ 未填"}")
            appendLine("📡 Chat ID：${if (chatId.isNotBlank()) chatId else "❌ 未填"}")
            appendLine("📸 相机：$cameraStatus")
            appendLine("🧠 模型：$modelStatus")
            appendLine("⏱ 间隔：${Config.intervalMinutes} 分钟")
            appendLine("🔍 AI识别：${if (Config.detectionEnabled) "✅ 开启" else "❌ 关闭"}")
            appendLine("🐛 调试：${if (Config.debugMode) "✅ 开启" else "❌ 关闭"}")
            appendLine("⏰ 最近拍照：$lastCap")
            appendLine("📊 拍照耗时：${CameraService.lastCaptureDurationMs}ms")
            appendLine("🧠 AI耗时：${CameraService.lastAiDurationMs}ms")
            appendLine("🔋 电量：$battery%")
            appendLine("💾 JVM：${usedMB}MB / ${maxMB}MB")
            appendLine("🔍 检测：$detectionText")
        }
        tvDebug.text = text
    }
}
