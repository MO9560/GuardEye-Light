package com.guardeye.light

import com.guardeye.light.LightAlarmReceiver
import com.guardeye.light.LightAlarmReceiverTicket

import com.guardeye.light.LightBotService

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.databinding.LightActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class LightMainActivity : AppCompatActivity() {

    private lateinit var ui: LightActivityMainBinding
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        registerReceiver(captureReceiver, IntentFilter(LightBotService.ACTION_CAPTURE_UPDATED))
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(captureReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
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
        // Interval slider
        ui.sliderInterval.addOnChangeListener { _, value, _ ->
            val mins = value.toInt()
            Config.intervalMinutes = mins
            updateIntervalText(mins)
        }

        // 拍照
        ui.btnPhoto.setOnClickListener {
            if (!validateConfig()) return@setOnClickListener
            saveConfig()
            val svc = Intent(this, LightBotService::class.java).apply {
                action = LightBotService.ACTION_MANUAL_CAPTURE
            }
            startForegroundService(svc)
            Toast.makeText(this, "拍照中...", Toast.LENGTH_SHORT).show()
            ui.imageLastCapture.postDelayed({ loadLastCaptureImage() }, 2000)
        }

        // 查告票
        ui.btnCheckTicket.setOnClickListener {
            if (!validateTicketConfig()) return@setOnClickListener
            mainScope.launch {
                Toast.makeText(this@LightMainActivity, "正在查询告票...", Toast.LENGTH_SHORT).show()
                TicketChecker.checkAndPush()
                Toast.makeText(this@LightMainActivity, "查询完成", Toast.LENGTH_SHORT).show()
            }
        }

        // 主监控
        ui.btnStartStop.setOnClickListener {
            saveConfig()
            if (Config.enabled) {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                stopService(Intent(this, LightBotService::class.java))
                refreshStatus()
                Toast.makeText(this, "主监控已停止", Toast.LENGTH_SHORT).show()
            } else {
                if (!validateConfig()) return@setOnClickListener
                Config.enabled = true
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                startForegroundService(Intent(this, LightBotService::class.java))
                refreshStatus()
                Toast.makeText(this, "主监控已开启", Toast.LENGTH_SHORT).show()
            }
        }

        // 告票监控
        ui.btnTicketToggle.setOnClickListener {
            if (Config.ticketEnabled) {
                Config.ticketEnabled = false
                LightAlarmReceiver.cancelAlarm(this)
                refreshStatus()
                Toast.makeText(this, "告票监控已停止", Toast.LENGTH_SHORT).show()
            } else {
                if (!validateTicketConfig()) return@setOnClickListener
                Config.ticketEnabled = true
                LightAlarmReceiverTicket.scheduleAlarm(this, Config.ticketIntervalMinutes)
                mainScope.launch {
                    TicketChecker.checkAndPush()
                }
                refreshStatus()
                Toast.makeText(this, "告票监控已开启（每${Config.ticketIntervalMinutes}分钟）", Toast.LENGTH_SHORT).show()
            }
        }

        // Battery hint
        ui.textBatteryHint.setOnClickListener {
            val intent = Intent(this, LightBotService::class.java).apply {
                action = LightBotService.ACTION_REQUEST_BATTERY
            }
            startForegroundService(intent)
            Toast.makeText(this, "请选择「不限」或「不优化」", Toast.LENGTH_LONG).show()
        }

        // Nav
        ui.tabSettings.setOnClickListener {
            startActivity(Intent(this, LightSettingsActivity::class.java))
        }

        ui.tabHelp.setOnClickListener {
            Toast.makeText(this, "/start /stop /photo /status /ticket /test",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        // Main monitoring status
        val running = Config.enabled
        ui.btnStartStop.text = if (running) "关闭主监控" else "开启主监控"
        ui.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this,
            if (running) R.color.candy_running else R.color.candy_cam)

        ui.cardStatus.setBackgroundResource(
            if (running) R.drawable.card_candy_start else R.drawable.card_candy_stop)
        ui.textStatus.text = "●"
        ui.labelStatus.text = if (running) "运行" else "停止"

        // Ticket monitoring status
        val ticketRunning = Config.ticketEnabled
        ui.btnTicketToggle.text = if (ticketRunning) "关闭告票" else "开启告票"
        ui.btnTicketToggle.backgroundTintList = ContextCompat.getColorStateList(this,
            if (ticketRunning) R.color.candy_running else R.color.candy_cam)

        ui.cardTicket.setBackgroundResource(
            if (ticketRunning) R.drawable.card_candy_start else R.drawable.card_candy_stop)
        ui.textTicketStatus.text = if (ticketRunning) "●" else "○"
        ui.labelTicket.text = if (ticketRunning) "${Config.ticketIntervalMinutes}分" else "停止"

        // Header badge
        if (ticketRunning) {
            ui.badgeTicket.visibility = android.view.View.VISIBLE
            ui.textTicketBadge.text = "告票(${Config.ticketIntervalMinutes}分钟)"
            ui.dotTicket.background.setTint(getColor(R.color.candy_running))
        } else {
            ui.badgeTicket.visibility = android.view.View.GONE
        }

        // Interval
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

    private fun validateTicketConfig(): Boolean {
        if (Config.botToken.isBlank() || Config.chatId.isBlank()) {
            Toast.makeText(this, "请先在设置中填写 Token 和 Chat ID", Toast.LENGTH_LONG).show()
            return false
        }
        if (Config.ticketPlates.isBlank()) {
            Toast.makeText(this, "请先在设置中添加车牌列表", Toast.LENGTH_LONG).show()
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
        refreshStatus()
    }
}
