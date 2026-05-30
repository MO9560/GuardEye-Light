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
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GuardEye.Main"
    }

    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var sliderInterval: SeekBar
    private lateinit var tvInterval: TextView
    private lateinit var switchDetection: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchDebug: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var btnSave: Button
    private lateinit var btnStart: Button
    private lateinit var tvBotStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvDebug: TextView
    private lateinit var cardDebug: View
    private lateinit var btnToggleDebug: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private var debugExpanded = false

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
            Toast.makeText(this, "Camera and network permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.e(TAG, ">>> onCreate START")
        super.onCreate(savedInstanceState)
        android.util.Log.e(TAG, ">>> super.onCreate done")

        // ── Step 1: Config.init ─────────────────────────────────────
        try {
            Config.init(this)
            android.util.Log.e(TAG, ">>> Config.init OK")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "!!! Config.init CRASH: ${e.message}", e)
            throw e
        }

        // ── Step 2: setContentView — most likely crash point ─────────
        try {
            setContentView(R.layout.activity_main)
            android.util.Log.e(TAG, ">>> setContentView OK")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "!!! setContentView CRASH: ${e.message}", e)
            throw e
        }

        // ── Step 3: preloadModel (full only) ──────────────────────────
        try {
            CameraService.preloadModel(this)
            android.util.Log.e(TAG, ">>> preloadModel OK")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "!!! preloadModel CRASH: ${e.message}", e)
            // Don't rethrow — non-fatal for UI
        }

        // ── Step 4: initViews ────────────────────────────────────────
        try {
            initViews()
            android.util.Log.e(TAG, ">>> initViews OK")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "!!! initViews CRASH: ${e.message}", e)
            throw e
        }

        // ── Step 5: loadConfig + setupListeners ──────────────────────
        try {
            loadConfig()
            android.util.Log.e(TAG, ">>> loadConfig OK")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "!!! loadConfig CRASH: ${e.message}", e)
            throw e
        }

        try {
            setupListeners()
            android.util.Log.e(TAG, ">>> setupListeners OK")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "!!! setupListeners CRASH: ${e.message}", e)
            throw e
        }

        android.util.Log.e(TAG, ">>> onCreate ALL DONE")
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
        cardDebug       = findViewById(R.id.cardDebug)
        btnToggleDebug  = findViewById(R.id.btnToggleDebug)
        tvVersion.text  = "v${BuildConfig.VERSION_NAME}"
    }

    private fun loadConfig() {
        etBotToken.setText(Config.botToken)
        etChatId.setText(Config.chatId)
        sliderInterval.progress = (Config.intervalMinutes - 1).coerceIn(0, 9)
        tvInterval.text = "${Config.intervalMinutes} min"
        switchDetection.isChecked = Config.detectionEnabled
        switchDebug.isChecked = Config.debugMode
        refreshBotStatusUI()
    }

    private fun setupListeners() {
        // Interval slider
        sliderInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val mins = (progress + 1).coerceIn(1, 10)
                tvInterval.text = "$mins min"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Save button
        btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "Config saved", Toast.LENGTH_SHORT).show()
        }

        // Start / Stop button
        btnStart.setOnClickListener {
            if (Config.enabled) {
                stopAll()
            } else {
                saveConfig()
                if (!Config.isConfigured) {
                    Toast.makeText(this, "Fill in Bot Token and Chat ID first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                checkPermissionsAndStart()
            }
        }

        // Debug panel expand/collapse
        cardDebug.setOnClickListener {
            debugExpanded = !debugExpanded
            tvDebug.maxLines = if (debugExpanded) Int.MAX_VALUE else 4
            btnToggleDebug.setImageResource(
                if (debugExpanded) android.R.drawable.arrow_up_float
                else android.R.drawable.arrow_down_float
            )
        }
    }

    // ── Config ──────────────────────────────────────────────────

    private fun saveConfig() {
        Config.botToken         = etBotToken.text.toString().trim()
        Config.chatId           = etChatId.text.toString().trim()
        Config.intervalMinutes  = sliderInterval.progress + 1
        Config.detectionEnabled = switchDetection.isChecked
        Config.debugMode        = switchDebug.isChecked
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
        Config.botToken        = etBotToken.text.toString().trim()
        Config.chatId          = etChatId.text.toString().trim()
        Config.intervalMinutes = sliderInterval.progress + 1

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

        // 2. Send startup message via Telegram
        Handler(Looper.getMainLooper()).postDelayed({
            TelegramBot.sendText(
                Config.botToken, Config.chatId,
                "GuardEye started\nInterval: ${Config.intervalMinutes}min\nDebug: ${if (Config.debugMode) "On" else "Off"}"
            )
        }, 2000)

        // 3. Take first photo immediately
        Handler(Looper.getMainLooper()).postDelayed({
            val camIntent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_CAPTURE
                putExtra(CameraService.EXTRA_SOURCE, CameraService.SOURCE_MANUAL)
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
        Toast.makeText(this, "GuardEye started", Toast.LENGTH_SHORT).show()
    }

    private fun stopAll() {
        Config.enabled = false

        AlarmReceiver.cancelAlarm(this)

        val botIntent = Intent(this, BotService::class.java).apply {
            action = BotService.ACTION_STOP
        }
        startService(botIntent)

        val camIntent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(camIntent)

        TelegramBot.sendText(Config.botToken, Config.chatId, "GuardEye stopped")

        refreshBotStatusUI()
        Toast.makeText(this, "GuardEye stopped", Toast.LENGTH_SHORT).show()
    }

    // ── UI Refresh ──────────────────────────────────────────────

    private fun refreshBotStatusUI() {
        val tokenOk = Config.botToken.isNotBlank()
        val chatOk = Config.chatId.isNotBlank()
        val running = Config.enabled

        tvBotStatus.apply {
            text = when {
                !tokenOk || !chatOk -> "Not configured"
                running -> "Running"
                else -> "Stopped"
            }
            setTextColor(ContextCompat.getColor(this@MainActivity,
                when {
                    !tokenOk || !chatOk -> R.color.amber
                    running -> R.color.green
                    else -> R.color.text_secondary
                }
            ))
        }

        btnStart.apply {
            text = if (running) "Stop" else "Start"
            setBackgroundColor(ContextCompat.getColor(this@MainActivity,
                if (running) ContextCompat.getColor(this@MainActivity, R.color.red)
                else ContextCompat.getColor(this@MainActivity, R.color.accent)
            ))
        }
    }

    private fun refreshDebugPanel() {
        val token = Config.botToken
        val chatId = Config.chatId

        val rt = Runtime.getRuntime()
        val usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val maxMB  = rt.maxMemory() / 1024 / 1024

        val modelFile = File(filesDir, "yolov8n.tflite")
        val modelStatus = if (modelFile.exists()) "Loaded" else "Not loaded"

        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 0

        val cameraReady = if (CameraService.isModelReady) "Ready" else "Initializing"

        fun fmtTime(ts: Long): String =
            if (ts > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
            else "—"

        val lastCap  = fmtTime(CameraService.lastCaptureTime)
        val srcLabel = if (CameraService.lastSource == CameraService.SOURCE_INTERVAL) "Auto" else "Manual"
        val detection = CameraService.lastDetectionText.ifBlank { "None" }

        val text = buildString {
            appendLine("Bot: ${if (token.isNotBlank()) "OK" else "Not set"}")
            appendLine("Chat ID: ${if (chatId.isNotBlank()) chatId else "Not set"}")
            appendLine("Camera: $cameraReady")
            appendLine("Model: $modelStatus")
            appendLine("Interval: ${Config.intervalMinutes} min")
            appendLine("AI Detection: ${if (Config.detectionEnabled) "On" else "Off"}")
            appendLine("Debug: ${if (Config.debugMode) "On" else "Off"}")
            appendLine("Last capture: $lastCap ($srcLabel)")
            appendLine("Capture time: ${CameraService.lastCaptureDurationMs}ms")
            appendLine("AI time: ${CameraService.lastAiDurationMs}ms")
            appendLine("Battery: $battery%")
            appendLine("JVM: ${usedMB}MB / ${maxMB}MB")
            appendLine("Detection: $detection")
        }
        tvDebug.text = text
    }
}
