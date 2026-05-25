package com.guardeye

import com.guardeye.BuildConfig
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.BotManager.BotStatus
import com.guardeye.BotManager.BotStatusListener

/**
 * MainActivity — GuardEye 主界面
 *
 * 功能：
 * - 配置 Bot Token / Chat ID
 * - 启动/停止 Bot 服务（独立运行）
 * - 启动/停止相机监控
 * - 实时显示 Bot 连接状态
 * - 版本信息展示
 */
class MainActivity : AppCompatActivity(), BotStatusListener {

    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var sliderInterval: SeekBar
    private lateinit var tvInterval: TextView
    private lateinit var switchEnabled: Switch
    private lateinit var switchDetection: Switch
    private lateinit var btnSave: Button
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvBotStatus: TextView
    private lateinit var tvVersion: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    // 每 2 秒刷新一次状态
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshBotStatus()
            mainHandler.postDelayed(this, 2000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            requestIgnoreBatteryOpt()
        } else {
            Toast.makeText(this, "需要相机和通知权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Config.init(this)
        BotManager.addListener(this)

        initViews()
        loadConfig()
        setupListeners()
        refreshBotStatus()
    }

    private fun initViews() {
        etBotToken = findViewById(R.id.etBotToken)
        etChatId = findViewById(R.id.etChatId)
        sliderInterval = findViewById(R.id.sliderInterval)
        tvInterval = findViewById(R.id.tvInterval)
        switchEnabled = findViewById(R.id.switchEnabled)
        switchDetection = findViewById(R.id.switchDetection)
        btnSave = findViewById(R.id.btnSave)
        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.tvStatus)
        tvBotStatus = findViewById(R.id.tvBotStatus)
        tvVersion = findViewById(R.id.tvVersion)
        tvVersion.text = "v${BuildConfig.VERSION_NAME}"
    }

    private fun loadConfig() {
        etBotToken.setText(Config.botToken)
        etChatId.setText(Config.chatId)
        sliderInterval.progress = Config.intervalMinutes - 1
        tvInterval.text = "${Config.intervalMinutes} 分钟"
        switchEnabled.isChecked = Config.enabled
        switchDetection.isChecked = Config.detectionEnabled
    }

    private fun setupListeners() {
        sliderInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvInterval.text = "${(progress + 1).coerceIn(1, 10)} 分钟"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Config.isConfigured) {
                Toast.makeText(this, "请先填写 Bot Token 和 Chat ID", Toast.LENGTH_SHORT).show()
                switchEnabled.isChecked = false
            }
        }

        btnSave.setOnClickListener {
            Config.botToken = etBotToken.text.toString().trim()
            Config.chatId = etChatId.text.toString().trim()
            Config.intervalMinutes = sliderInterval.progress + 1
            Config.detectionEnabled = switchDetection.isChecked
            // 同步到 BotManager
            BotManager.setToken(Config.botToken)
            BotManager.setChatId(Config.chatId)
            Toast.makeText(this, "✅ 配置已保存", Toast.LENGTH_SHORT).show()
        }

        btnStart.setOnClickListener {
            // 保存配置
            Config.botToken = etBotToken.text.toString().trim()
            Config.chatId = etChatId.text.toString().trim()
            Config.intervalMinutes = sliderInterval.progress + 1
            Config.enabled = switchEnabled.isChecked
            Config.detectionEnabled = switchDetection.isChecked
            // 同步到 BotManager
            BotManager.setToken(Config.botToken)
            BotManager.setChatId(Config.chatId)

            if (Config.botToken.isBlank() || Config.chatId.isBlank()) {
                Toast.makeText(this, "请填写 Bot Token 和 Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (switchEnabled.isChecked) {
                checkAndRequestPermissions()
            } else {
                stopAllServices()
            }
        }
    }

    private fun checkAndRequestPermissions() {
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
            requestIgnoreBatteryOpt()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestIgnoreBatteryOpt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        startAllServices()
    }

    private fun startAllServices() {
        // 1. 启动 Bot 服务（独立于相机）
        val botIntent = Intent(this, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(botIntent)
        } else {
            startService(botIntent)
        }

        // 2. 启动相机服务（监控+拍照）
        val camIntent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(camIntent)
        } else {
            startService(camIntent)
        }

        Config.enabled = true
        switchEnabled.isChecked = true
        Toast.makeText(this, "🚀 GuardEye 已启动（Bot + 相机）", Toast.LENGTH_SHORT).show()
    }

    private fun stopAllServices() {
        val stopIntent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(stopIntent)

        val botStopIntent = Intent(this, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_STOP
        }
        startService(botStopIntent)

        Config.enabled = false
        switchEnabled.isChecked = false
        Toast.makeText(this, "⏹ GuardEye 已停止", Toast.LENGTH_SHORT).show()
    }

    // ========== BotStatusListener 实现 ==========

    override fun onBotConnected() {
        runOnUiThread {
            tvBotStatus.text = "✅ Bot 已连接"
            tvBotStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        }
    }

    override fun onBotDisconnected() {
        runOnUiThread {
            tvBotStatus.text = "🔴 Bot 连接中断"
            tvBotStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
        }
    }

    override fun onCommandReceived(text: String, chatId: String) {
        // 收到命令时闪烁提示
        runOnUiThread {
            Toast.makeText(this, "📩 收到指令：$text", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOffsetUpdated(offset: Long) {
        // 后台更新，无需 UI 反馈
    }

    // ========== 状态刷新 ==========

    private fun refreshBotStatus() {
        val status = BotManager.getStatus()
        val modelFile = java.io.File(filesDir, "yolov8n.tflite")
        val modelLoaded = modelFile.exists()

        val botStatusText = when {
            !status.isTokenSet -> "⚠️ Token 未配置"
            !status.isChatIdSet -> "⚠️ Chat ID 未配置"
            status.isPolling -> "✅ Bot 运行中 · offset=${
                if (status.lastOffset > 0) status.lastOffset.toString() else "0"
            }"
            else -> "🔴 Bot 已停止"
        }

        tvStatus.text = buildString {
            append("状态：").append(if (Config.enabled) "✅ 运行中" else "❌ 已停止")
            append("\n模型：").append(if (modelLoaded) "✅ 已加载" else "⚠️ 未加载")
            append("\nChat ID：").append(if (Config.chatId.isNotBlank()) Config.chatId else "未设置")
            append("\nBot Token：").append(if (Config.botToken.isNotBlank()) "${Config.botToken.take(8)}..." else "未设置")
        }

        runOnUiThread {
            tvBotStatus.text = botStatusText
            tvBotStatus.setTextColor(
                ContextCompat.getColor(this,
                    when {
                        !status.isTokenSet || !status.isChatIdSet -> R.color.red
                        status.isPolling -> R.color.green
                        else -> R.color.red
                    }
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(refreshRunnable)
        refreshBotStatus()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        BotManager.removeListener(this)
        mainHandler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }
}
