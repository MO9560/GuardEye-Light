package com.guardeye.light

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        if (Config.cameraFacing == "front") ui.radioFront.isChecked = true
        else ui.radioBack.isChecked = true
    }

    private fun setupListeners() {
        ui.btnSettingsBack.setOnClickListener { finish() }

        // Tab switching
        ui.tabBasic.setOnClickListener {
            ui.contentBasic.visibility = View.VISIBLE
            ui.contentCamera.visibility = View.GONE
            ui.tabBasic.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            ui.tabBasic.setTextColor(ContextCompat.getColor(this, R.color.white))
            ui.tabCamera.setBackgroundColor(ContextCompat.getColor(this, R.color.card_bg))
            ui.tabCamera.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
        ui.tabCamera.setOnClickListener {
            ui.contentBasic.visibility = View.GONE
            ui.contentCamera.visibility = View.VISIBLE
            ui.tabCamera.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            ui.tabCamera.setTextColor(ContextCompat.getColor(this, R.color.white))
            ui.tabBasic.setBackgroundColor(ContextCompat.getColor(this, R.color.card_bg))
            ui.tabBasic.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        // Token toggle visibility
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
        Config.cameraFacing = if (ui.radioFront.isChecked) "front" else "back"
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }
}