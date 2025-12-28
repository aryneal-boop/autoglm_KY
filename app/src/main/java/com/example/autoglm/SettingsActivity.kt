package com.example.autoglm

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
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
        val rgExecEnv = findViewById<RadioGroup>(R.id.rgExecutionEnvironment)
        val rbExecEnvMain = findViewById<RadioButton>(R.id.rbExecEnvMain)
        val rbExecEnvVirtual = findViewById<RadioButton>(R.id.rbExecEnvVirtual)
        val btnMonitor = findViewById<Button>(R.id.btnMonitor)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnCancel = findViewById<Button>(R.id.btnCancel)

        etBaseUrl.setText(config.baseUrl)
        etApiKey.setText(config.apiKey)
        etModelName.setText(config.modelName)

        val execEnv = configManager.getExecutionEnvironment()
        if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
            rbExecEnvVirtual.isChecked = true
        } else {
            rbExecEnvMain.isChecked = true
        }

        btnMonitor.setOnClickListener {
            val did = try {
                VirtualDisplayController.ensureVirtualDisplayForMonitor(this)
            } catch (_: Exception) {
                null
            }
            if (did == null) {
                try {
                    Toast.makeText(this, "虚拟屏创建失败", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
                return@setOnClickListener
            }

            try {
                startActivity(Intent(this, MonitorActivity::class.java))
            } catch (_: Exception) {
                try {
                    Toast.makeText(this, "无法打开监视器界面", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
        }

        btnSave.setOnClickListener {
            val newConfig = ConfigManager.ModelServiceConfig(
                baseUrl = etBaseUrl.text?.toString().orEmpty(),
                apiKey = etApiKey.text?.toString().orEmpty(),
                modelName = etModelName.text?.toString().orEmpty(),
            )
            configManager.setConfig(newConfig)

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
