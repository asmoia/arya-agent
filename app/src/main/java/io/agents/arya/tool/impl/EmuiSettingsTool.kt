// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Fork of PokeClaw — https://github.com/agents-io/PokeClaw

package io.agents.arya.tool.impl

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.agents.arya.ClawApplication
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult

/**
 * EMUI/Huawei-specific settings control tool.
 */
class EmuiSettingsTool : BaseTool() {

    override fun getName(): String = "emui_settings"
    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("action", "string", true, "Action: open_appgallery, open_phone_manager, open_settings, brightness, wifi, bluetooth, battery, storage, sound, display, apps, device_info"),
        ToolParameter("level", "integer", false, "Brightness level 0-255 (only for brightness action)")
    )
    override fun getDescriptionEN(): String = "Control Huawei EMUI-specific settings (AppGallery, Phone Manager, brightness, WiFi, Bluetooth, battery, etc.)"
    override fun getDescriptionCN(): String = "控制华为 EMUI 设置（应用市场、手机管家、亮度、WiFi、蓝牙、电池等）"

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase() ?: return ToolResult.error("Missing 'action' parameter")
        val app = ClawApplication.instance

        return when (action) {
            "open_appgallery" -> openApp(app, "com.huawei.appmarket", "com.huawei.appmarket.MainActivity")
            "open_phone_manager" -> openApp(app, "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.OptimizeActivity")
            "open_settings" -> openSettings(app, Settings.ACTION_SETTINGS, "تنظیمات")
            "brightness" -> setBrightness(app, params)
            "wifi" -> openSettings(app, Settings.ACTION_WIFI_SETTINGS, "وای‌فای")
            "bluetooth" -> openSettings(app, Settings.ACTION_BLUETOOTH_SETTINGS, "بلوتوث")
            "battery" -> openSettings(app, Intent.ACTION_POWER_USAGE_SUMMARY, "باتری")
            "storage" -> openSettings(app, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "فضای ذخیره")
            "sound" -> openSettings(app, Settings.ACTION_SOUND_SETTINGS, "صدا")
            "display" -> openSettings(app, Settings.ACTION_DISPLAY_SETTINGS, "نمایش")
            "apps" -> openSettings(app, Settings.ACTION_APPLICATION_SETTINGS, "اپ‌ها")
            "device_info" -> getDeviceInfo()
            else -> ToolResult.error("Unknown action: $action")
        }
    }

    private fun openApp(context: Context, pkg: String, cls: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClassName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success("App opened: $pkg")
        } catch (e: Exception) {
            ToolResult.error("Cannot open $pkg: ${e.message}")
        }
    }

    private fun openSettings(context: Context, action: String, label: String): ToolResult {
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ToolResult.success("Opened: $label")
        } catch (e: Exception) {
            ToolResult.error("Cannot open $label: ${e.message}")
        }
    }

    private fun setBrightness(context: Context, params: Map<String, Any>): ToolResult {
        val level = optionalInt(params, "level", 128).coerceIn(0, 255)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            ToolResult.success("Brightness set to $level/255")
        } catch (e: Exception) {
            ToolResult.error("Write Settings permission needed. ADB: pm grant ${context.packageName} android.permission.WRITE_SETTINGS")
        }
    }

    private fun getDeviceInfo(): ToolResult {
        val sb = StringBuilder()
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Brand: ${Build.BRAND}\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        return ToolResult.success(sb.toString().trim())
    }
}
