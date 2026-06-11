package com.guardeye.light

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
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
    }

    private fun setupListeners() {
        ui.btnSettingsBack.setOnClickListener { finish() }

        ui.btnToggleToken.setOnClickListener {
            if (ui.editToken.transformationMethod is PasswordTransformationMethod) {
                ui.editToken.transformationMethod = HideReturnsTransformationMethod.getInstance()
                ui.btnToggleToken.setImageResource(R.drawable.ic_eye_off_outline)
            } else {
                ui.editToken.transformationMethod = PasswordTransformationMethod.getInstance()
                ui.btnToggleToken.setImageResource(R.drawable.ic_eye_outline)
            }
        }

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
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}