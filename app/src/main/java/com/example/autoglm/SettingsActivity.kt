package com.example.autoglm

import android.os.Bundle
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.example.autoglm.ui.NeonLiquidBackground

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        try {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        } catch (_: Exception) {
        }

        val composeBackground = findViewById<ComposeView>(R.id.composeBackground)
        composeBackground.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0E14))
            ) {
                NeonLiquidBackground(modifier = Modifier.fillMaxSize())
            }
        }

        val configManager = ConfigManager(this)
        val config = configManager.getConfig()

        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val etVirtualDisplayDpi = findViewById<EditText>(R.id.etVirtualDisplayDpi)
        val spVirtualDisplayResolution = findViewById<Spinner>(R.id.spVirtualDisplayResolution)
        val rgExecEnv = findViewById<RadioGroup>(R.id.rgExecutionEnvironment)
        val rbExecEnvMain = findViewById<RadioButton>(R.id.rbExecEnvMain)
        val rbExecEnvVirtual = findViewById<RadioButton>(R.id.rbExecEnvVirtual)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        etBaseUrl.setText(config.baseUrl)
        etApiKey.setText(config.apiKey)
        etModelName.setText(config.modelName)
        etVirtualDisplayDpi.setText(configManager.getVirtualDisplayDpi().toString())

        val resolutionItems = listOf(
            ConfigManager.RES_480P,
            ConfigManager.RES_720P,
            ConfigManager.RES_1080P,
        )
        val resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutionItems)
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spVirtualDisplayResolution.adapter = resolutionAdapter
        val savedResolution = configManager.getVirtualDisplayResolutionPreset()
        val selectionIndex = resolutionItems.indexOf(savedResolution).takeIf { it >= 0 } ?: 0
        spVirtualDisplayResolution.setSelection(selectionIndex)

        val execEnv = configManager.getExecutionEnvironment()
        if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
            rbExecEnvVirtual.isChecked = true
        } else {
            rbExecEnvMain.isChecked = true
        }

        btnSave.setOnClickListener {
            val newConfig = ConfigManager.ModelServiceConfig(
                baseUrl = etBaseUrl.text?.toString().orEmpty(),
                apiKey = etApiKey.text?.toString().orEmpty(),
                modelName = etModelName.text?.toString().orEmpty(),
            )
            configManager.setConfig(newConfig)

            val newDpi = etVirtualDisplayDpi.text?.toString().orEmpty().trim().toIntOrNull()
                ?.takeIf { it in 72..640 }
                ?: ConfigManager.DEFAULT_VIRTUAL_DISPLAY_DPI
            configManager.setVirtualDisplayDpi(newDpi)

            val selectedResolution = spVirtualDisplayResolution.selectedItem as? String
            if (!selectedResolution.isNullOrBlank()) {
                configManager.setVirtualDisplayResolutionPreset(selectedResolution)
            }

            val selected = rgExecEnv.checkedRadioButtonId
            val newExecEnv = if (selected == R.id.rbExecEnvVirtual) {
                ConfigManager.EXEC_ENV_VIRTUAL
            } else {
                ConfigManager.EXEC_ENV_MAIN
            }
            configManager.setExecutionEnvironment(newExecEnv)

            if (newExecEnv != ConfigManager.EXEC_ENV_VIRTUAL) {
                try {
                    VirtualDisplayController.cleanupAsync(this)
                } catch (_: Exception) {
                }
            }
            finish()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }
}
