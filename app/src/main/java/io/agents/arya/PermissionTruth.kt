package io.agents.arya

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.agents.arya.service.ClawAccessibilityService
import io.agents.arya.service.ClawNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PermissionState(
    val isAccessibilityEnabled: Boolean = false,
    val isNotificationListenerEnabled: Boolean = false,
    val isOverlayEnabled: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isProcessStartGraceActive: Boolean = false
) {
    val isAllCriticalGranted: Boolean
        get() = isAccessibilityEnabled && isOverlayEnabled
}

class PermissionTruth(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val processStartTime = System.currentTimeMillis()
    private val GRACE_PERIOD_MS = 30_000L // 30s process-start grace hack

    private val _state = MutableStateFlow(PermissionState())
    val state: StateFlow<PermissionState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val isAcc = isAccessibilityServiceEnabled()
            val isNotif = isNotificationListenerEnabled()
            val isOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isBatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else true

            val isGrace = (System.currentTimeMillis() - processStartTime) < GRACE_PERIOD_MS

            _state.value = PermissionState(
                isAccessibilityEnabled = isAcc,
                isNotificationListenerEnabled = isNotif,
                isOverlayEnabled = isOverlay,
                isBatteryOptimizationIgnored = isBatt,
                isProcessStartGraceActive = isGrace
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val serviceName = "${context.packageName}/${ClawAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(serviceName)
        } catch (_: Exception) {
            false
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            flat.contains(context.packageName)
        } catch (_: Exception) {
            false
        }
    }
}
