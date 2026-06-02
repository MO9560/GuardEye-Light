package com.guardeye.light

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.PasswordTransformationMethod

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.Config
import com.guardeye.light.ACTION_MANUAL_CAPTURE
import com.guardeye.light.ACTION_REQUEST_BATTERY
import com.guardeye.R
import com.guardeye.databinding.LightActivityMainBinding

/**
 * LightMainActivity — card-style settings UI for GuardEye Light.
 */
class LightMainActivity : AppCompatActivity() {

    private lateinit var ui: LightActivityMainBinding
    private var tokenVisible = false

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
        updateIntervalText(Config.intervalMinutes)
        ui.switchDebug.isChecked = Config.debugMode

        // Hide token by default
        tokenVisible = false
        ui.inputToken.transformationMethod = PasswordTransformationMethod.getInstance()
    }

    private fun setupListeners() {
        // Token visibility toggle
        ui.btnToggleToken.setOnClickListener {
            tokenVisible = !tokenVisible
            if (tokenVisible) {
                ui.inputToken.transformationMethod = null
                ui.btnToggleToken.setImageResource(R.drawable.ic_eye_off_outline)
            } else {
                ui.inputToken.transformationMethod = PasswordTransformationMethod.getInstance()
                ui.btnToggleToken.setImageResource(R.drawable.ic_eye_outline)
            }
            // Move cursor to end
                ui.inputToken.setSelection(ui.inputToken.text?.length ?: 0)
        }

        // Interval slider
        ui.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            Config.intervalMinutes = mins
            updateIntervalText(mins)
        }

        // Start/Stop button
        ui.btnStartStop.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()

            if (Config.enabled) {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                stopService(Intent(this, LightBotService::class.java))
                refreshStatus()
            } else {
                Config.enabled = true
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                startForegroundService(Intent(this, LightBotService::class.java))
                refreshStatus()
            }
        }

        // Photo capture (tap the photo icon column)
        ui.btnPhoto.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()
            val svc = Intent(this, LightBotService::class.java).apply {
                action = ACTION_MANUAL_CAPTURE
            }
            startForegroundService(svc)
            Toast.makeText(this, "拍照中...", Toast.LENGTH_SHORT).show()
        }

        // Battery hint tap → open settings
        ui.textBatteryHint.setOnClickListener {
            val intent = Intent(this, LightBotService::class.java).apply {
                action = ACTION_REQUEST_BATTERY
            }
            startForegroundService(intent)
            Toast.makeText(this, "请选择「不限」或「不优化」", Toast.LENGTH_LONG).show()
        }

        // Bottom tabs
        ui.tabSettings.setOnClickListener { /* already on settings */ }
        ui.tabHelp.setOnClickListener {
            Toast.makeText(this, "/start /stop /photo /status /interval N /debug /battery /test",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        val running = Config.enabled
        ui.btnStartStop.text = if (running) "停止监控" else "开始监控"
        ui.btnStartStop.backgroundTintList =
            androidx.core.content.ContextCompat.getColorStateList(this,
                if (running) R.color.red else R.color.primary)

        // Status dot color
        ui.dotStatus.setBackgroundResource(
            if (running) R.drawable.status_dot_green else R.drawable.status_dot)

        // Update interval text with status
        val mins = Config.intervalMinutes
        ui.textInterval.text = mins.toString() + "\u5206"
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

    private fun updateIntervalText(mins: Int) {
        ui.textInterval.text = mins.toString() + "\u5206"
    }
}
