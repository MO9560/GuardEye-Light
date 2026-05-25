package com.guardeye

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var sliderInterval: SeekBar
    private lateinit var tvInterval: TextView
    private lateinit var switchEnabled: Switch
    private lateinit var switchDetection: Switch
    private lateinit var btnSave: Button
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            startService()
        } else {
            Toast.makeText(this, "需要相机和通知权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Config.init(this)

        initViews()
        loadConfig()
        setupListeners()
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
    }

    private fun loadConfig() {
        etBotToken.setText(Config.botToken)
        etChatId.setText(Config.chatId)
        sliderInterval.progress = Config.intervalMinutes - 1
        tvInterval.text = "${Config.intervalMinutes} 分钟"
        switchEnabled.isChecked = Config.enabled
        switchDetection.isChecked = Config.detectionEnabled
        updateStatus()
    }

    private fun setupListeners() {
        sliderInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val mins = (progress + 1).coerceIn(1, 10)
                tvInterval.text = "$mins 分钟"
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
            Toast.makeText(this, "✅ 配置已保存", Toast.LENGTH_SHORT).show()
        }

        btnStart.setOnClickListener {
            Config.botToken = etBotToken.text.toString().trim()
            Config.chatId = etChatId.text.toString().trim()
            Config.intervalMinutes = sliderInterval.progress + 1
            Config.enabled = switchEnabled.isChecked
            Config.detectionEnabled = switchDetection.isChecked

            if (Config.botToken.isBlank() || Config.chatId.isBlank()) {
                Toast.makeText(this, "请填写 Bot Token 和 Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (switchEnabled.isChecked) {
                checkAndRequestPermissions()
            } else {
                stopService()
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
            // 申请电池优化豁免
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
        startService()
    }

    private fun startService() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Config.enabled = true
        switchEnabled.isChecked = true
        updateStatus()
        Toast.makeText(this, "🚀 GuardEye 已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopService() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(intent)
        Config.enabled = false
        switchEnabled.isChecked = false
        updateStatus()
        Toast.makeText(this, "⏹ GuardEye 已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val modelFile = java.io.File(filesDir, "yolov8n.tflite")
        val modelLoaded = modelFile.exists()

        tvStatus.text = buildString {
            append("状态：")
            append(if (Config.enabled) "✅ 运行中" else "❌ 已停止")
            append("\n模型：")
            append(if (modelLoaded) "✅ yolov8n.tflite 已加载" else "⚠️ 未加载（首次启动后自动从assets复制）")
            if (Config.chatId.isNotBlank()) {
                append("\nChat ID：").append(Config.chatId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
