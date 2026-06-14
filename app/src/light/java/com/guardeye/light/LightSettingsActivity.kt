package com.guardeye.light

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.databinding.LightActivitySettingsBinding

class LightSettingsActivity : AppCompatActivity() {

    private lateinit var ui: LightActivitySettingsBinding

    // Tab content containers
    private lateinit var ticketView: View
    private lateinit var tgView: View

    // TG robot tab views
    private lateinit var editToken: EditText
    private lateinit var editChatId: EditText
    private lateinit var btnToggleToken: ImageButton
    private lateinit var btnSaveTg: Button

    // Ticket tab views
    private lateinit var editPlates: EditText
    private lateinit var btnSaveTicket: Button
    private val intervalBtns = mutableMapOf<Int, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)
        ui = LightActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ticketView = createTicketTab()
        tgView = createTgTab()

        setupTabs()
        loadConfig()
        setupListeners()
    }

    private fun createTgTab(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val ctx = this

        // Token card
        val tokenCard = createCard(ctx).apply {
            addView(TextView(ctx).apply {
                text = "Bot Token"
                setTextAppearance(android.R.style.TextAppearance_Medium)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            })
        }
        val tokenRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        editToken = EditText(ctx).apply {
            hint = "输入 Telegram Bot Token"
            inputType = (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            isSingleLine = true
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary)))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        btnToggleToken = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_eye_outline)
            background = ContextCompat.getDrawable(ctx, R.drawable.btn_icon_circle)
            setColorFilter(ContextCompat.getColor(ctx, R.color.text_hint))
            contentDescription = "显隐"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) }
        }
        tokenRow.addView(editToken); tokenRow.addView(btnToggleToken)
        tokenCard.addView(tokenRow); container.addView(tokenCard)

        // Chat ID card
        val chatCard = createCard(ctx).apply {
            addView(TextView(ctx).apply {
                text = "Chat ID"
                setTextAppearance(android.R.style.TextAppearance_Medium)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            })
        }
        editChatId = EditText(ctx).apply {
            hint = "输入 Chat ID"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary)))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        chatCard.addView(editChatId); container.addView(chatCard)

        // Save button
        btnSaveTg = Button(ctx).apply {
            text = "保存设置"
            setTextColor(ContextCompat.getColor(ctx, R.color.white))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.primary))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
        }
        container.addView(btnSaveTg)

        return container
    }

    private fun createTicketTab(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val ctx = this

        // Interval card
        val intervalCard = createCard(ctx).apply {
            addView(TextView(ctx).apply {
                text = "查询间隔"
                setTextAppearance(android.R.style.TextAppearance_Medium)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            })
            addView(TextView(ctx).apply {
                text = "告票监控自动查询的时间间隔"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) }
            })
        }
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val intervals = listOf(5 to "5分钟", 10 to "10分钟", 15 to "15分钟", 20 to "20分钟", 30 to "30分钟")
        intervals.forEachIndexed { i, (mins, label) ->
            val btn = Button(ctx).apply {
                text = label; tag = mins
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                setBackgroundColor(ContextCompat.getColor(ctx, android.R.color.transparent))
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (i % 3 != 2) marginEnd = dp(6)
                }
                intervalBtns[mins] = this
            }
            if (i < 3) row1.addView(btn) else row2.addView(btn)
        }
        // Row 2 spacer
        row2.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f) })
        intervalCard.addView(row1); intervalCard.addView(row2)
        container.addView(intervalCard)

        // Plate list card
        val plateCard = createCard(ctx).apply {
            addView(TextView(ctx).apply {
                text = "车牌列表"
                setTextAppearance(android.R.style.TextAppearance_Medium)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            })
            addView(TextView(ctx).apply {
                text = "每行一个，如 MO2541（2大写字母+4位数字）"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_hint)); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) }
            })
        }
        editPlates = EditText(ctx).apply {
            hint = "MO2541\nAX4521\nMT9200"
            minLines = 5; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary)))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(120)).apply { topMargin = dp(8) }
        }
        plateCard.addView(editPlates); container.addView(plateCard)

        // Save button
        btnSaveTicket = Button(ctx).apply {
            text = "保存告票设置"
            setTextColor(ContextCompat.getColor(ctx, R.color.white))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.primary))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
        }
        container.addView(btnSaveTicket)

        return container
    }

    private fun createCard(ctx: android.content.Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ContextCompat.getDrawable(ctx, R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setupTabs() {
        ui.tabLayout.addTab(ui.tabLayout.newTab().setText("告票"))
        ui.tabLayout.addTab(ui.tabLayout.newTab().setText("TG机器人"))
        ui.tabContent.addView(ticketView)

        ui.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                ui.tabContent.removeAllViews()
                when (tab.position) {
                    0 -> ui.tabContent.addView(ticketView)
                    1 -> ui.tabContent.addView(tgView)
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        updateIntervalButtons()
    }

    private fun loadConfig() {
        editToken.setText(Config.botToken)
        editChatId.setText(Config.chatId)
        editPlates.setText(Config.ticketPlates)
        updateIntervalButtons()
    }

    private fun setupListeners() {
        ui.btnSettingsBack.setOnClickListener { finish() }

        btnToggleToken.setOnClickListener {
            if (editToken.transformationMethod is PasswordTransformationMethod) {
                editToken.transformationMethod = HideReturnsTransformationMethod.getInstance()
                btnToggleToken.setImageResource(R.drawable.ic_eye_off_outline)
            } else {
                editToken.transformationMethod = PasswordTransformationMethod.getInstance()
                btnToggleToken.setImageResource(R.drawable.ic_eye_outline)
            }
        }

        btnSaveTg.setOnClickListener {
            val token = editToken.text.toString().trim()
            val chatId = editChatId.text.toString().trim()
            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Token 和 Chat ID 不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Config.botToken = token
            Config.chatId = chatId
            Toast.makeText(this, "TG机器人设置已保存", Toast.LENGTH_SHORT).show()
        }

        for ((mins, btn) in intervalBtns) {
            btn.setOnClickListener {
                Config.ticketIntervalMinutes = mins
                updateIntervalButtons()
            }
        }

        btnSaveTicket.setOnClickListener {
            Config.ticketPlates = editPlates.text.toString().trim()
            val status = if (Config.ticketEnabled) "已开启" else "已关闭"
            Toast.makeText(this, "告票设置已保存（$status）", Toast.LENGTH_SHORT).show()
            // Schedule or cancel the alarm
            if (Config.ticketEnabled && Config.ticketPlates.isNotBlank()) {
                LightAlarmReceiverTicket.scheduleAlarm(this, Config.ticketIntervalMinutes)
            } else {
                LightAlarmReceiverTicket.cancelAlarm(this)
            }
        }
    }

    private fun updateIntervalButtons() {
        val active = Config.ticketIntervalMinutes
        for ((mins, btn) in intervalBtns) {
            if (mins == active) {
                btn.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                btn.setTextColor(ContextCompat.getColor(this, R.color.white))
            } else {
                btn.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
    }
}
