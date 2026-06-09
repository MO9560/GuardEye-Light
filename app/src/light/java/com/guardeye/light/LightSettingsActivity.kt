package com.guardeye.light

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.databinding.LightActivitySettingsBinding

class LightSettingsActivity : AppCompatActivity() {

    private lateinit var ui: LightActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(this)
        ui = LightActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)

        loadConfig()
        setupListeners()
    }

    private fun loadConfig() {
        ui.editToken.setText(Config.botToken)
        ui.editChatId.setText(Config.chatId)
        when (Config.cameraFacing) {
            "front" -> ui.radioFront.isChecked = true
            else -> ui.radioBack.isChecked = true
        }
    }

    private fun setupListeners() {
        ui.btnSettingsBack.setOnClickListener { finish() }
        ui.btnSaveSettings.setOnClickListener { saveAndFinish() }
    }

    private fun saveAndFinish() {
        val token = ui.editToken.text.toString().trim()
        val chatId = ui.editChatId.text.toString().trim()

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Token 和 Chat ID 不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        Config.botToken = token
        Config.chatId = chatId
        Config.cameraFacing = if (ui.radioFront.isChecked) "front" else "back"

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}
