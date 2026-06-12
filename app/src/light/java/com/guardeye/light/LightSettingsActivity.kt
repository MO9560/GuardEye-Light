package com.guardeye.light

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.databinding.LightActivitySettingsBinding
import com.guardeye.databinding.TabBasicBinding
import com.guardeye.databinding.TabTicketBinding
import com.google.android.material.tabs.TabLayout

class LightSettingsActivity : AppCompatActivity() {

    private lateinit var ui: LightActivitySettingsBinding
    private lateinit var basicBinding: TabBasicBinding
    private lateinit var ticketBinding: TabTicketBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)
        ui = LightActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        // Inflate tab contents
        basicBinding = TabBasicBinding.inflate(layoutInflater)
        ticketBinding = TabTicketBinding.inflate(layoutInflater)

        setupTabs()
        loadConfig()
        setupListeners()
    }

    private fun setupTabs() {
        ui.tabLayout.addTab(ui.tabLayout.newTab().setText("基本"))
        ui.tabLayout.addTab(ui.tabLayout.newTab().setText("告票"))

        // Show basic tab by default
        ui.tabContent.addView(basicBinding.root)
        updateIntervalButtons()

        ui.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                ui.tabContent.removeAllViews()
                when (tab.position) {
                    0 -> ui.tabContent.addView(basicBinding.root)
                    1 -> ui.tabContent.addView(ticketBinding.root)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun loadConfig() {
        basicBinding.editToken.setText(Config.botToken)
        basicBinding.editChatId.setText(Config.chatId)
        ticketBinding.editPlates.setText(Config.ticketPlates)
        updateIntervalButtons()
    }

    private fun setupListeners() {
        ui.btnSettingsBack.setOnClickListener { finish() }

        // Basic tab
        basicBinding.btnToggleToken.setOnClickListener {
            if (basicBinding.editToken.transformationMethod is PasswordTransformationMethod) {
                basicBinding.editToken.transformationMethod = HideReturnsTransformationMethod.getInstance()
                basicBinding.btnToggleToken.setImageResource(R.drawable.ic_eye_off_outline)
            } else {
                basicBinding.editToken.transformationMethod = PasswordTransformationMethod.getInstance()
                basicBinding.btnToggleToken.setImageResource(R.drawable.ic_eye_outline)
            }
        }

        basicBinding.btnSaveSettings.setOnClickListener {
            val token = basicBinding.editToken.text.toString().trim()
            val chatId = basicBinding.editChatId.text.toString().trim()
            if (token.isEmpty() || chatId.isEmpty()) {
                Toast.makeText(this, "Token 和 Chat ID 不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Config.botToken = token
            Config.chatId = chatId
            Toast.makeText(this, "基本设置已保存", Toast.LENGTH_SHORT).show()
        }

        // Ticket tab interval buttons
        val intervalBtns = listOf(
            ticketBinding.btnInterval5 to 5,
            ticketBinding.btnInterval10 to 10,
            ticketBinding.btnInterval15 to 15,
            ticketBinding.btnInterval20 to 20,
            ticketBinding.btnInterval30 to 30
        )
        for ((btn, mins) in intervalBtns) {
            btn.setOnClickListener {
                Config.ticketIntervalMinutes = mins
                updateIntervalButtons()
            }
        }

        ticketBinding.btnSaveTicket.setOnClickListener {
            Config.ticketPlates = ticketBinding.editPlates.text.toString().trim()
            Toast.makeText(this, "告票设置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateIntervalButtons() {
        val active = Config.ticketIntervalMinutes
        val intervalBtns = listOf(
            ticketBinding.btnInterval5 to 5,
            ticketBinding.btnInterval10 to 10,
            ticketBinding.btnInterval15 to 15,
            ticketBinding.btnInterval20 to 20,
            ticketBinding.btnInterval30 to 30
        )
        for ((btn, mins) in intervalBtns) {
            if (btn != null) {
                if (mins == active) {
                    btn.setBackgroundColor(getColor(R.color.primary))
                    btn.setTextColor(getColor(R.color.white))
                } else {
                    btn.setBackgroundColor(getColor(android.R.color.transparent))
                    btn.setTextColor(getColor(R.color.text_primary))
                }
            }
        }
    }
}
