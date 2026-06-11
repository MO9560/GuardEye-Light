package com.guardeye.light

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.databinding.LightActivityMainBinding
import java.io.File

class LightMainActivity : AppCompatActivity() {

    private lateinit var ui: LightActivityMainBinding

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadLastCaptureImage()
        }
    }

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

    override fun onStart() {
        super.onStart()
        registerReceiver(captureReceiver, IntentFilter(ACTION_CAPTURE_UPDATED))
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(captureReceiver) } catch (_: Exception) {}
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
        loadLastCaptureImage()
    }

    private fun loadLastCaptureImage() {
        val path = Config.lastCapturePath
        if (path != null && File(path).exists()) {
            try {
                val bm = android.graphics.BitmapFactory.decodeFile(path)
                if (bm != null) ui.imageLastCapture.setImageBitmap(bm)
            } catch (_: Exception) {}
        }
    }

    private fun setupListeners() {
        ui.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            Config.intervalMinutes = mins
            updateIntervalText(mins)
        }

        ui.btnStartStop.setOnClickListener {
            saveConfig()
            if (Config.enabled) {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                stopService(Intent(this, LightBotService::class.java))
                refreshStatus()
            } else {
                if (!validateConfig()) return@setOnClickListener
                Config.enabled = true
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                startForegroundService(Intent(this, LightBotService::class.java))
                refreshStatus()
            }
        }

        ui.btnPhoto.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()
            val svc = Intent(this, LightBotService::class.java).apply {
                action = ACTION_MANUAL_CAPTURE
            }
            startForegroundService(svc)
            Toast.makeText(this, "拍照中...", Toast.LENGTH_SHORT).show()
        }

        ui.textBatteryHint.setOnClickListener {
            val intent = Intent(this, LightBotService::class.java).apply {
                action = ACTION_REQUEST_BATTERY
            }
            startForegroundService(intent)
            Toast.makeText(this, "请选择「不限」或「不优化」", Toast.LENGTH_LONG).show()
        }

        ui.tabSettings.setOnClickListener {
            startActivity(Intent(this, LightSettingsActivity::class.java))
        }

        ui.tabHelp.setOnClickListener {
            Toast.makeText(this, "/start /stop /photo /status /interval N /debug /battery /test",
                Toast.LENGTH_LONG).show()
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

        val mins = Config.intervalMinutes
        ui.textInterval.text = "${mins}分"
        updateTemp()
    }

    private fun updateTemp() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
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
        loadLastCaptureImage()
    }
}