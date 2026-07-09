// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Fork of PokeClaw — https://github.com/agents-io/PokeClaw

package io.agents.arya.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.agents.arya.ClawApplication
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolResult

/**
 * EMUI/Huawei-specific settings control tool.
 *
 * Provides quick access to common Huawei device settings that are not
 * accessible through the standard Android Settings API on EMUI 14.2.
 */
class EmuiSettingsTool : BaseTool("emui_settings", "Control Huawei EMUI-specific settings") {

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase() ?: return ToolResult.error("Missing 'action' parameter")
        val app = ClawApplication.instance

        return when (action) {
            "open_appgallery" -> openAppGallery(app)
            "open_phone_manager" -> openPhoneManager(app)
            "open_huawei_settings" -> openHuaweiSettings(app)
            "brightness" -> setBrightness(app, params)
            "wifi" -> openWifiSettings(app)
            "bluetooth" -> openBluetoothSettings(app)
            "data_usage" -> openDataUsageSettings(app)
            "battery" -> openBatterySettings(app)
            "storage" -> openStorageSettings(app)
            "sound" -> openSoundSettings(app)
            "display" -> openDisplaySettings(app)
            "apps" -> openAppSettings(app)
            "security" -> openSecuritySettings(app)
            "about" -> openAboutSettings(app)
            "developer" -> openDeveloperSettings(app)
            "accessibility" -> openAccessibilitySettings(app)
            "notification_settings" -> openNotificationSettings(app)
            "device_info" -> getDeviceInfo()
            else -> ToolResult.error("Unknown action: $action. Valid: open_appgallery, open_phone_manager, brightness, wifi, bluetooth, battery, storage, sound, display, apps, device_info")
        }
    }

    private fun openAppGallery(context: Context): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName("com.huawei.appmarket", "com.huawei.appmarket.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success("AppGallery باز شد")
        } catch (e: Exception) {
            ToolResult.error("نتونستم AppGallery باز کنم: ${e.message}")
        }
    }

    private fun openPhoneManager(context: Context): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.OptimizeActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success("Phone Manager باز شد")
        } catch (e: Exception) {
            ToolResult.error("نتونستم Phone Manager باز کنم: ${e.message}")
        }
    }

    private fun openHuaweiSettings(context: Context): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success("تنظیمات باز شد")
        } catch (e: Exception) {
            ToolResult.error("خطا: ${e.message}")
        }
    }

    private fun setBrightness(context: Context, params: Map<String, Any>): ToolResult {
        val level = (params["level"]?.toString()?.toIntOrNull() ?: 128).coerceIn(0, 255)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            ToolResult.success("روشنایی تنظیم شد: $level/255")
        } catch (e: Exception) {
            ToolResult.error("دسترسی Write Settings لازمه. با ADB: pm grant ${context.packageName} android.permission.WRITE_SETTINGS")
        }
    }

    private fun openWifiSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_WIFI_SETTINGS, "تنظیمات وای‌فای")
    }

    private fun openBluetoothSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_BLUETOOTH_SETTINGS, "تنظیمات بلوتوث")
    }

    private fun openDataUsageSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_DATA_USAGE_SETTINGS, "مصرف دیتا")
    }

    private fun openBatterySettings(context: Context): ToolResult {
        return openSettingsAction(context, Intent.ACTION_POWER_USAGE_SUMMARY, "تنظیمات باتری")
    }

    private fun openStorageSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "تنظیمات فضای ذخیره")
    }

    private fun openSoundSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_SOUND_SETTINGS, "تنظیمات صدا")
    }

    private fun openDisplaySettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_DISPLAY_SETTINGS, "تنظیمات نمایش")
    }

    private fun openAppSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_APPLICATION_SETTINGS, "مدیریت اپ‌ها")
    }

    private fun openSecuritySettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_SECURITY_SETTINGS, "تنظیمات امنیتی")
    }

    private fun openAboutSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_DEVICE_INFO_SETTINGS, "درباره گوشی")
    }

    private fun openDeveloperSettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "گزینه‌های توسعه‌دهنده")
    }

    private fun openAccessibilitySettings(context: Context): ToolResult {
        return openSettingsAction(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, "دسترسی‌پذیری")
    }

    private fun openNotificationSettings(context: Context): ToolResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            openSettingsAction(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS, "تنظیمات اعلان")
        } else {
            ToolResult.error("نیاز به اندروید ۸ به بالا")
        }
    }

    private fun openSettingsAction(context: Context, action: String, label: String): ToolResult {
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ToolResult.success("$label باز شد")
        } catch (e: Exception) {
            ToolResult.error("نتونستم $label باز کنم: ${e.message}")
        }
    }

    private fun getDeviceInfo(): ToolResult {
        val sb = StringBuilder()
        sb.append("📱 مدل: ${Build.MODEL}\n")
        sb.append("🖥️ اندروید: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("🏭 سازنده: ${Build.MANUFACTURER}\n")
        sb.append("🏷️ برند: ${Build.BRAND}\n")
        try {
            val emui = Build.VERSION.INCREMENTAL ?: "نامشخص"
            sb.append("🔄 EMUI: $emui\n")
        } catch (_: Exception) {}
        sb.append("⚙️ Kernel: ${Build.DISPLAY}\n")
        return ToolResult.success(sb.toString().trim())
    }
}
