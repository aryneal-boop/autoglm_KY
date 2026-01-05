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

/**
 * 设置页 Activity（XML + Compose 背景）。
 *
 * **用途**
 * - 配置模型服务参数（baseUrl/apiKey/modelName），保存到 [ConfigManager]。
 * - 配置执行环境：
 *   - 主屏控制（[ConfigManager.EXEC_ENV_MAIN]）
 *   - 虚拟隔离模式（[ConfigManager.EXEC_ENV_VIRTUAL]）
 * - 配置虚拟屏参数：DPI、分辨率预设。
 * - 当用户切回主屏模式时，会触发 [VirtualDisplayController.cleanupAsync] 尝试停止虚拟屏。
 *
 * **引用路径**
 * - `app/src/main/AndroidManifest.xml` -> `<activity android:name=".SettingsActivity" ...>`
 * - 通常由 `ChatActivity` 的设置按钮启动。
 *
 * **使用注意事项**
 * - `apiKey` 属于敏感信息：由 [ConfigManager] 使用加密 SharedPreferences 保存。
 * - 切换执行环境会影响截图/输入注入路径：建议提示用户在任务空闲时切换。
 */
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
