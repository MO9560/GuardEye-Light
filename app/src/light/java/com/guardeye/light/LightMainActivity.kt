package com.guardeye.light

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.databinding.LightActivityMainBinding

/**
 * LightMainActivity — simplified settings UI for GuardEye Light.
 * No AI controls, no camera preview. Just bot config + start/stop.
 */
class LightMainActivity : AppCompatActivity() {

    private lateinit var ui: LightActivityMainBinding

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
        ui.inputToken.setText(Config.botToken)
        ui.inputChatId.setText(Config.chatId)
        ui.sliderInterval.value = Config.intervalMinutes.toFloat()
        ui.textInterval.text = "间隔：${Config.intervalMinutes} 分钟"
        ui.switchDebug.isChecked = Config.debugMode
    }

    private fun setupListeners() {
        ui.btnSave.setOnClickListener {
            Config.botToken = ui.inputToken.text.toString().trim()
            Config.chatId = ui.inputChatId.text.toString().trim()
            Config.debugMode = ui.switchDebug.isChecked
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        ui.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            Config.intervalMinutes = mins
            ui.textInterval.text = "间隔：$mins 分钟"
        }

        ui.btnStartStop.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()

            if (Config.enabled) {
                // Stop
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                stopService(Intent(this, LightBotService::class.java))
                refreshStatus()
            } else {
                // Start
                Config.enabled = true
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                val svc = Intent(this, LightBotService::class.java)
                startForegroundService(svc)
                refreshStatus()
            }
        }

        ui.btnPhoto.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()
            val svc = Intent(this, LightBotService::class.java).apply {
                action = LightBotService.ACTION_CAPTURE
            }
            startForegroundService(svc)
            Toast.makeText(this, "拍照中...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val running = Config.enabled
        ui.textStatus.text = if (running) "✅ 运行中" else "⏸ 已停止"
        ui.btnStartStop.text = if (running) "停止监控" else "启动监控"
        ui.btnStartStop.setBackgroundColor(
            ContextCompat.getColor(this,
                if (running) R.color.status_red else R.color.status_green))
    }

    private fun validateConfig(): Boolean {
        val token = ui.inputToken.text.toString().trim()
        val chatId = ui.inputChatId.text.toString().trim()
        if (token.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "请先填写 Token 和 Chat ID", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun saveConfig() {
        Config.botToken = ui.inputToken.text.toString().trim()
        Config.chatId = ui.inputChatId.text.toString().trim()
        Config.debugMode = ui.switchDebug.isChecked
    }
}
