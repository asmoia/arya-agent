// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agents.arya.ClawApplication
import io.agents.arya.R
import io.agents.arya.base.BaseActivity
import io.agents.arya.widget.AlertDialog
import io.agents.arya.widget.ConfirmDialog
import io.agents.arya.widget.CommonToolbar
import io.agents.arya.widget.InputDialog
import io.agents.arya.widget.MenuGroup
import io.agents.arya.widget.MenuItem
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.AppRequirement
import io.agents.arya.appViewModel
import io.agents.arya.server.ConfigServerManager
import io.agents.arya.service.ForegroundService
import io.agents.arya.support.DebugReportManager
import io.agents.arya.agent.hermes.backup.HermesBackupManager
import io.agents.arya.utils.KVUtils
import io.agents.arya.agent.hermes.core.HermesRuntimePolicy
import io.agents.arya.agent.hermes.core.HermesThinkingMode
import io.agents.arya.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen
 */
class SettingsActivity : BaseActivity() {

    // Poll permissions every second (same as original HomeActivity)
    private val handler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            refreshPermissions()
            handler.postDelayed(this, 1000)
        }
    }

    // Permission menu items — kept for onResume refresh
    private var permAccessibility: io.agents.arya.widget.MenuItem? = null
    private var permNotification: io.agents.arya.widget.MenuItem? = null
    private var permNotifAccess: io.agents.arya.widget.MenuItem? = null
    private var permOverlay: io.agents.arya.widget.MenuItem? = null
    private var permBattery: io.agents.arya.widget.MenuItem? = null
    private var permStorage: io.agents.arya.widget.MenuItem? = null
    private var externalAutomationItem: io.agents.arya.widget.MenuItem? = null
    private var hermesCoreItem: io.agents.arya.widget.MenuItem? = null
    private var thinkingModeItem: io.agents.arya.widget.MenuItem? = null
    private var sensitiveConfirmItem: io.agents.arya.widget.MenuItem? = null
    private var globalPromptItem: io.agents.arya.widget.MenuItem? = null
    private var customModelUrlItem: io.agents.arya.widget.MenuItem? = null

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // Keep MenuItem references for dynamic updates
    private val menuItems = mutableMapOf<String, MenuItem>()

    // Register launcher to refresh after returning from LLM config screen
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    // Register channel config result callback
    
    /** SAF picker for Hermes backup ZIP import */
    private val hermesImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                HermesBackupManager.importFromUri(this@SettingsActivity, uri, replaceAll = false)
            }
            Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
            XLog.i("SettingsActivity", "hermes import: ${result.message}")
        }
    }

private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
        result?.let {
            // Refresh settings after successful config (refresh "Bound"/"Unbound" status)
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force match theme from ThemeManager
        val themeColors = io.agents.arya.ui.chat.ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg
        window.decorView.setBackgroundColor(themeColors.bg)

        setContentView(R.layout.activity_settings)

        // Override XML backgrounds with ThemeManager colors
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(themeColors.bg)
        // Root LinearLayout has android:background="@color/colorBgPrimary" — override it
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(themeColors.bg)

        initToolbar()
        initMenuGroups()
        initSettingsSearch()
        applyThemeToGroups(themeColors)
        observeViewModel()
    }

    private fun initSettingsSearch() {
        val box = findViewById<android.widget.EditText>(R.id.settingsSearch) ?: return
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applySettingsFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applySettingsFilter(query: String) {
        val visible = SettingsSearch.visibleGroups(SettingsCatalog.defaultRows(this), query)
        val showAll = query.isBlank()
        fun show(id: Int, group: SettingsGroup, extra: Boolean = true) {
            findViewById<android.view.View>(id)?.visibility =
                if (showAll || (extra && visible.contains(group))) android.view.View.VISIBLE
                else android.view.View.GONE
        }
        show(R.id.permissionsGroup, SettingsGroup.PERMISSIONS)
        show(R.id.modelGroup, SettingsGroup.MODEL)
        show(R.id.appearanceGroup, SettingsGroup.ADVANCED)
        show(R.id.toolsGroup, SettingsGroup.VOICE)
        show(R.id.remoteGroup, SettingsGroup.ADVANCED)
        show(R.id.aboutGroup, SettingsGroup.ADVANCED)
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        refreshPermissions()
        refreshExternalAutomation()
        refreshHermesAndSafety()
        handler.removeCallbacks(permPoller)
        handler.postDelayed(permPoller, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(permPoller)
    }

    private fun refreshPermissions() {
        val capabilities = AppCapabilityCoordinator.snapshot(this)
        permAccessibility?.setTrailingText(capabilities.accessibilityStatusLabel)
        permNotification?.setTrailingText(capabilities.notificationPermissionStatusLabel)
        permNotifAccess?.setTrailingText(capabilities.notificationAccessStatusLabel)
        permOverlay?.setTrailingText(if (capabilities.overlayGranted) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled))
        permBattery?.setTrailingText(if (capabilities.batteryOptimizationIgnored) getString(R.string.settings_status_unrestricted) else getString(R.string.settings_status_restricted))
        permStorage?.setTrailingText(if (capabilities.storageAccessGranted) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled))
    }

    private fun refreshExternalAutomation() {
        externalAutomationItem?.setTrailingText(
            if (KVUtils.isExternalAutomationEnabled()) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled)
        )
    }

    private fun refreshHermesAndSafety() {
        hermesCoreItem?.setTrailingText(
            if (KVUtils.isHermesEmbeddedEnabled()) getString(R.string.settings_hermes_status_enabled) else getString(R.string.settings_hermes_status_disabled)
        )
        sensitiveConfirmItem?.setTrailingText(
            if (KVUtils.isSensitiveConfirmEnabled()) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled)
        )
    }

    private fun toggleHermesCore() {
        val currently = KVUtils.isHermesEmbeddedEnabled()
        if (currently) {
            ConfirmDialog.showWarm(
                context = this,
                title = getString(R.string.settings_hermes_disable_title),
                message = getString(R.string.settings_hermes_disable_message),
                actionTitle = getString(R.string.settings_hermes_disable_action),
                cancelTitle = getString(R.string.common_cancel),
                onAction = {
                    KVUtils.setHermesEmbeddedEnabled(false)
                    ClawApplication.appViewModelInstance.updateAgentConfig()
                    refreshHermesAndSafety()
                    Toast.makeText(this, R.string.settings_hermes_disabled, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            KVUtils.setHermesEmbeddedEnabled(true)
            ClawApplication.appViewModelInstance.updateAgentConfig()
            refreshHermesAndSafety()
            Toast.makeText(this, R.string.settings_hermes_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSensitiveConfirm() {
        val currently = KVUtils.isSensitiveConfirmEnabled()
        if (currently) {
            ConfirmDialog.showWarm(
                context = this,
                title = getString(R.string.settings_sensitive_disable_title),
                message = getString(R.string.settings_sensitive_disable_message),
                actionTitle = getString(R.string.settings_sensitive_disable_action),
                cancelTitle = getString(R.string.common_cancel),
                onAction = {
                    KVUtils.setSensitiveConfirmEnabled(false)
                    refreshHermesAndSafety()
                    Toast.makeText(this, R.string.settings_sensitive_disabled, Toast.LENGTH_LONG).show()
                }
            )
        } else {
            KVUtils.setSensitiveConfirmEnabled(true)
            refreshHermesAndSafety()
            Toast.makeText(this, R.string.settings_sensitive_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    /** Refreshes the trailing label on the global-prompt row (#45). */

    private fun showOemGuide() {
        AlertDialog.show(
            context = this,
            title = getString(R.string.settings_oem_guide_title),
            message = getString(R.string.settings_oem_guide_message),
            actionTitle = getString(R.string.common_confirm)
        )
    }


    
    private fun thinkingModeLabel(mode: HermesThinkingMode): String = when (mode) {
        HermesThinkingMode.ADAPTIVE -> getString(R.string.settings_thinking_adaptive)
        HermesThinkingMode.INSTANT -> getString(R.string.settings_thinking_instant)
        HermesThinkingMode.THINKING -> getString(R.string.settings_thinking_thinking)
        HermesThinkingMode.HIGH -> getString(R.string.settings_thinking_high)
    }

    private fun cycleThinkingMode() {
        val order = listOf(
            HermesThinkingMode.INSTANT,
            HermesThinkingMode.ADAPTIVE,
            HermesThinkingMode.THINKING,
            HermesThinkingMode.HIGH
        )
        val cur = HermesRuntimePolicy.currentMode()
        val idx = order.indexOf(cur).let { if (it < 0) 0 else it }
        val next = order[(idx + 1) % order.size]
        HermesRuntimePolicy.setMode(next)
        thinkingModeItem?.setTrailingText(thinkingModeLabel(next))
        val detail = when (next) {
            HermesThinkingMode.ADAPTIVE -> getString(R.string.settings_thinking_adaptive_detail)
            HermesThinkingMode.INSTANT -> getString(R.string.settings_thinking_instant_detail)
            HermesThinkingMode.THINKING -> getString(R.string.settings_thinking_thinking_detail)
            HermesThinkingMode.HIGH -> getString(R.string.settings_thinking_high_detail)
        }
        Toast.makeText(this, getString(R.string.settings_task_mode_toast, thinkingModeLabel(next), detail), Toast.LENGTH_LONG).show()
    }

    private fun exportHermesBackup() {
        Toast.makeText(this, R.string.settings_backup_in_progress, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                HermesBackupManager.exportToCache(this@SettingsActivity)
            }
            if (!result.ok || result.file == null) {
                Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${packageName}.fileprovider",
                    result.file
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_backup_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.settings_backup_share_chooser)))
            } catch (e: Exception) {
                XLog.e("SettingsActivity", "share backup failed", e)
                Toast.makeText(this@SettingsActivity, getString(R.string.settings_share_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun refreshGlobalPromptStatus() {
        val current = KVUtils.getGlobalPrompt()
        val label = if (current.isBlank()) {
            getString(R.string.global_prompt_not_set)
        } else {
            getString(R.string.global_prompt_set_status, current.length)
        }
        globalPromptItem?.setTrailingText(label)
    }

    /** Refreshes the trailing label on the custom-model-URL row (#36). */
    private fun refreshCustomModelUrlStatus() {
        val current = KVUtils.getCustomLocalModelUrl()
        val label = if (current.isBlank()) {
            getString(R.string.custom_local_model_url_not_set)
        } else {
            getString(R.string.custom_local_model_url_set)
        }
        customModelUrlItem?.setTrailingText(label)
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun applyThemeToGroups(tc: io.agents.arya.ui.chat.ThemeManager.ChatColors) {
        val groups = listOf(
            R.id.permissionsGroup, R.id.channelGroup, R.id.modelGroup,
            R.id.appearanceGroup, R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup
        )
        for (id in groups) {
            val g = findViewById<MenuGroup>(id) ?: continue
            g.setTitleColor(tc.aiText)
            g.setCardBackgroundColor(tc.toolbarBg)
            for (i in 0 until g.getMenuItemCount()) {
                g.getMenuItemAt(i)?.apply {
                    setTitleColor(tc.aiText)
                    setTrailingTextColor(tc.sendColor)
                    setLeadingIconColor(tc.aiText)
                    setTrailingIconColor(tc.aiText)
                }
            }
        }
        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar)?.apply {
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }
    }

    private fun refreshSettings() {
        viewModel.refresh()
    }

    private fun toggleExternalAutomation() {
        if (KVUtils.isExternalAutomationEnabled()) {
            KVUtils.setExternalAutomationEnabled(false)
            refreshExternalAutomation()
            Toast.makeText(this, R.string.settings_external_automation_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        ConfirmDialog.showWarm(
            context = this,
            title = getString(R.string.settings_external_automation_enable_title),
            message = getString(R.string.settings_external_automation_enable_message),
            actionTitle = getString(R.string.settings_external_automation_enable_action),
            cancelTitle = getString(R.string.common_cancel),
            onAction = {
                KVUtils.setExternalAutomationEnabled(true)
                refreshExternalAutomation()
                Toast.makeText(this, R.string.settings_external_automation_enabled, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun initMenuGroups() {
        // Permissions
        val permissionsGroup = findViewById<MenuGroup>(R.id.permissionsGroup)
        permissionsGroup.setTitle(getString(R.string.settings_group_permissions))

        permAccessibility = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_accessibility,
            title = getString(R.string.home_card_accessibility_title),
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.ACCESSIBILITY)
                Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
            },
            showDivider = true
        )

        permNotification = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.home_card_notification_title),
            onClick = {
                if (!AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(
                                    if (AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled)

            )
        }

        permNotifAccess = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.settings_notification_access),
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.NOTIFICATION_ACCESS)
            },
            showDivider = true
        )

        permOverlay = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.home_card_system_window_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).overlayGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.OVERLAY)
                }
            },
            showDivider = true
        )

        permBattery = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = getString(R.string.home_card_battery_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).batteryOptimizationIgnored) {
                    Toast.makeText(this@SettingsActivity, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.BATTERY_OPTIMIZATION)
                }
            },
            showDivider = true
        )

        permStorage = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = getString(R.string.home_card_storage_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).storageAccessGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.STORAGE)
                }
            },
            showDivider = false
        )

        // Channel (hidden)
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

        menuItems[SettingsViewModel.MenuAction.DISCORD.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_discord,
            title = getString(R.string.menu_discord),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DISCORD) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.TELEGRAM.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_telegram,
            title = getString(R.string.menu_telegram),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.TELEGRAM) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.WECHAT.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_wechat,
            title = getString(R.string.menu_wechat),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.WECHAT) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LAN_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        channelGroup.visibility = android.view.View.GONE

        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // Task Budget (inline in model group)
        modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_recent_history,
            title = getString(R.string.settings_task_budget),
            onClick = { showBudgetDialog() },
            showDivider = true
        ).apply {
            setTrailingText(io.agents.arya.agent.TaskBudget.describeCurrentBudget())
        }

        // Global Prompt (#45) — user-defined persistent instructions
        globalPromptItem = modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_edit,
            title = getString(R.string.global_prompt_title),
            onClick = {
                val current = KVUtils.getGlobalPrompt()
                XLog.i("SettingsActivity", "open global prompt dialog: current.len=${current.length}")
                InputDialog.show(
                    context = this@SettingsActivity,
                    title = getString(R.string.global_prompt_dialog_title),
                    presetText = current,
                    hint = getString(R.string.global_prompt_hint),
                    maxLength = 2000,
                ) { text ->
                    KVUtils.setGlobalPrompt(text)
                    XLog.i("SettingsActivity", "global prompt saved: new.len=${text.length}, hasPrompt=${KVUtils.hasGlobalPrompt()}")
                    refreshGlobalPromptStatus()
                }
            },
            showDivider = false
        )
        globalPromptItem?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        refreshGlobalPromptStatus()

        // Custom Local Model URL (#36) — advanced: lets users add their own model download URL
        customModelUrlItem = modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = getString(R.string.custom_local_model_url_title),
            onClick = {
                val current = KVUtils.getCustomLocalModelUrl()
                XLog.i("SettingsActivity", "open custom model url dialog: current.len=${current.length}")
                InputDialog.show(
                    context = this@SettingsActivity,
                    title = getString(R.string.custom_local_model_url_dialog_title),
                    presetText = current,
                    hint = getString(R.string.custom_local_model_url_hint),
                    maxLength = 1000,
                    inputValidate = { text ->
                        val lower = text.trim().lowercase()
                        if (lower.isEmpty()) {
                            // Empty = clear; allow
                            io.agents.arya.widget.InputDialog.ValidateResult(true, null)
                        } else if (!lower.startsWith("https://")) {
                            io.agents.arya.widget.InputDialog.ValidateResult(
                                false,
                                getString(R.string.custom_local_model_url_invalid)
                            )
                        } else {
                            io.agents.arya.widget.InputDialog.ValidateResult(true, null)
                        }
                    },
                ) { text ->
                    // Normalize the protocol prefix to lowercase (Android keyboard auto-cap
                    // can produce "HTTPS://..."). Rest of the URL is case-preserved.
                    val trimmed = text.trim().let { raw ->
                        when {
                            raw.startsWith("HTTPS://", ignoreCase = false) -> "https://" + raw.substring(8)
                            raw.startsWith("HTTP://", ignoreCase = false) -> "http://" + raw.substring(7)
                            else -> raw
                        }
                    }
                    KVUtils.setCustomLocalModelUrl(trimmed)
                    XLog.i(
                        "SettingsActivity",
                        "custom local model url saved: new.len=${trimmed.length}, hasUrl=${KVUtils.hasCustomLocalModelUrl()}"
                    )
                    refreshCustomModelUrlStatus()
                }
            },
            showDivider = false
        )
        customModelUrlItem?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        refreshCustomModelUrlStatus()

        // Appearance
        val appearanceGroup = findViewById<MenuGroup>(R.id.appearanceGroup)
        appearanceGroup.setTitle(getString(R.string.settings_group_appearance))

        appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_slideshow,
            title = getString(R.string.settings_theme),
            onClick = {
                startActivity(Intent(this, ThemeActivity::class.java))
            },
            showDivider = false
        ).apply {
            val themeId = KVUtils.getString("THEME_ID", "abyss_dark")
            val label = themeId.replace("_", " ").replaceFirstChar { it.uppercase() }
            setTrailingText(label)
        }

        // Tools + Hermes safety
        val toolsGroup = findViewById<MenuGroup>(R.id.toolsGroup)
        toolsGroup.setTitle(getString(R.string.settings_group_tools))

        hermesCoreItem = toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_compass,
            title = getString(R.string.settings_hermes_core),
            onClick = { toggleHermesCore() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isHermesEmbeddedEnabled()) getString(R.string.settings_hermes_status_enabled) else getString(R.string.settings_hermes_status_disabled))
        }

        sensitiveConfirmItem = toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = getString(R.string.settings_sensitive_confirm),
            onClick = { toggleSensitiveConfirm() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isSensitiveConfirmEnabled()) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled))
        }

        thinkingModeItem = toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_sort_by_size,
            title = getString(R.string.settings_thinking_mode),
            onClick = { cycleThinkingMode() },
            showDivider = true
        ).apply {
            setTrailingText(thinkingModeLabel(HermesRuntimePolicy.currentMode()))
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = getString(R.string.settings_manage_tools),
            onClick = {
                Toast.makeText(
                    this,
                    getString(R.string.settings_gated_help),
                    Toast.LENGTH_LONG
                ).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_tools_gated))
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            title = getString(R.string.settings_backup_export),
            onClick = { exportHermesBackup() },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_zip))
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_upload,
            title = getString(R.string.settings_backup_restore_action) + " Hermes (Import)",
            onClick = {
                ConfirmDialog.show(
                    context = this,
                    title = getString(R.string.settings_backup_restore_title),
                    message = getString(R.string.settings_backup_restore_message),
                    actionTitle = getString(R.string.settings_backup_restore_action),
                    cancelTitle = getString(R.string.common_cancel),
                    onAction = {
                        hermesImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                )
            },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_zip))
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_btn_speak_now,
            title = getString(R.string.settings_voice_auto_send),
            onClick = {
                val next = !KVUtils.isVoiceAutoSend()
                KVUtils.setVoiceAutoSend(next)
                Toast.makeText(this, if (next) "Auto-send on" else "Auto-send off", Toast.LENGTH_SHORT).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isVoiceAutoSend()) "ON" else "OFF")
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_lock_silent_mode_off,
            title = getString(R.string.settings_voice_tts),
            onClick = {
                val next = !KVUtils.isVoiceTtsEnabled()
                KVUtils.setVoiceTtsEnabled(next)
                Toast.makeText(this, if (next) "TTS on" else "TTS off", Toast.LENGTH_SHORT).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isVoiceTtsEnabled()) "ON" else "OFF")
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_btn_speak_now,
            title = getString(R.string.settings_offline_stt),
            onClick = {
                val next = !KVUtils.isOfflineSttEnabled()
                KVUtils.setOfflineSttEnabled(next)
                Toast.makeText(
                    this,
                    if (next) getString(R.string.settings_offline_stt_on)
                    else getString(R.string.settings_offline_stt_off),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isOfflineSttEnabled()) "ON" else "OFF")
        }

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = getString(R.string.settings_oem_guide),
            onClick = { showOemGuide() },
            showDivider = false
        ).apply {
            setTrailingText(getString(R.string.settings_oem_guide_trailing))
        }

        // Remote Control
        val remoteGroup = findViewById<MenuGroup>(R.id.remoteGroup)
        remoteGroup.setTitle(getString(R.string.settings_remote_control))

        externalAutomationItem = remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = getString(R.string.settings_remote_control),
            onClick = { toggleExternalAutomation() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isExternalAutomationEnabled()) "Enabled" else "Disabled")
        }

        // About
        val aboutGroup = findViewById<MenuGroup>(R.id.aboutGroup)
        aboutGroup.setTitle(getString(R.string.settings_about))

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = "آریا (Arya)",
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText("v${io.agents.arya.BuildConfig.VERSION_NAME} · Hermes")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = getString(R.string.settings_report_bug),
            onClick = { reportBug() },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_github_zip))
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_upload,
            title = getString(R.string.settings_share_debug_report),
            onClick = { shareDebugReport() },
            showDivider = true
        ).apply {
            setTrailingText(getString(R.string.settings_zip_logs_state))
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "GitHub",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/asmoia/arya-agent".toUri()))
            },
            showDivider = true
        ).apply {
            setTrailingText("asmoia/arya-agent")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_compass,
            title = "Built by",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/ithiria894".toUri()))
            },
            showDivider = false
        ).apply {
            setTrailingText("ithiria894")
        }
    }

    private fun reportBug() {
        buildSupportBundle(
            preparingToast = getString(R.string.settings_preparing_debug_report)
        ) { report ->
            AlertDialog.show(
                context = this@SettingsActivity,
                title = getString(R.string.settings_bug_report_ready),
                message = getString(R.string.settings_bug_report_ready_message, report.name),
                actionTitle = getString(R.string.settings_open_github_issue),
                cancelTitle = getString(R.string.settings_share_zip),
                onAction = { openGitHubIssue(report) },
                onCancel = {
                    shareReportFile(
                        report = report,
                        chooserTitle = getString(R.string.settings_share_bug_report_chooser),
                        subject = getString(R.string.settings_debug_report_subject, io.agents.arya.BuildConfig.VERSION_NAME),
                        body = getString(R.string.settings_attach_github_body)
                    )
                }
            )
        }
    }

    private fun shareDebugReport() {
        buildSupportBundle(
            preparingToast = getString(R.string.settings_preparing_debug_report),
        ) { report ->
            shareReportFile(
                report = report,
                chooserTitle = getString(R.string.settings_debug_report_chooser),
                subject = getString(R.string.settings_share_debug_subject, io.agents.arya.BuildConfig.VERSION_NAME),
                body = getString(R.string.settings_share_debug_body)
            )
        }
    }

    private fun buildSupportBundle(
        preparingToast: String,
        onReportReady: (java.io.File) -> Unit,
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, preparingToast, Toast.LENGTH_SHORT).show()
            runCatching {
                withContext(Dispatchers.IO) {
                    DebugReportManager.buildReport(this@SettingsActivity)
                }
            }.onSuccess { report ->
                onReportReady(report)
            }.onFailure { error ->
                XLog.e("SettingsActivity", "Failed to build debug report", error)
                Toast.makeText(this@SettingsActivity, R.string.settings_debug_report_build_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGitHubIssue(report: java.io.File) {
        val issueUri = "https://github.com/asmoia/arya-agent/issues/new".toUri()
            .buildUpon()
            .appendQueryParameter(
                "title",
                "[Bug] ${Build.MANUFACTURER} ${Build.MODEL} - "
            )
            .appendQueryParameter("body", buildGitHubIssueBody(report))
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, issueUri))
            Toast.makeText(
                this,
                getString(R.string.settings_attach_file_after_open, report.name),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_no_app_github, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildGitHubIssueBody(report: java.io.File): String {
        return """
            ## What happened
            -

            ## What you expected
            -

            ## Exact steps to reproduce
            1.
            2.
            3.

            ## Device
            - Manufacturer: ${Build.MANUFACTURER}
            - Model: ${Build.MODEL}
            - Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})

            ## Attachments
            - Attach this ZIP from Arya: `${report.name}`
            - If this looks device-specific and you have ADB available, also attach `adb logcat`

            Generated by Arya ${io.agents.arya.BuildConfig.VERSION_NAME}.
        """.trimIndent()
    }

    private fun shareReportFile(
        report: java.io.File,
        chooserTitle: String,
        subject: String,
        body: String,
    ) {
        val uri = FileProvider.getUriForFile(
            this@SettingsActivity,
            "${packageName}.fileprovider",
            report
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this@SettingsActivity, R.string.settings_no_app_share_report, Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe settings changes and dynamically update UI
                launch {
                    viewModel.settingItems.collect { items ->
                        items.forEach { (key, value) ->
                            when (value) {
                                is SettingsViewModel.SettingValue.Text -> {
                                    menuItems[key]?.setTrailingText(value.text)
                                }
                                is SettingsViewModel.SettingValue.Switch -> {
                                    // Update switch state here if needed
                                }
                            }
                        }
                    }
                }

                // Observe H5 config changes (includes LLM/channel), refresh UI and re-initialize Agent and channels
                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                // Observe menu click events
                launch {
                    viewModel.menuClickEvent.collect { action ->
                        when (action) {
                            SettingsViewModel.MenuAction.WECHAT -> {
                                if (viewModel.isWechatBound()) {
                                    showUnbindDialog(getString(R.string.channel_wechat)) {
                                        viewModel.unbindWeChat()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.startWeChatQrLogin(this@SettingsActivity)
                                }
                            }
                            SettingsViewModel.MenuAction.DISCORD -> {
                                if (viewModel.isDiscordBound()) {
                                    showUnbindDialog(getString(R.string.channel_discord)) {
                                        viewModel.unbindDiscord()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DISCORD)
                                }
                            }
                            SettingsViewModel.MenuAction.TELEGRAM -> {
                                if (viewModel.isTelegramBound()) {
                                    showUnbindDialog(getString(R.string.channel_telegram)) {
                                        viewModel.unbindTelegram()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.TELEGRAM)
                                }
                            }
                            SettingsViewModel.MenuAction.LAN_CONFIG -> {
                                val result = viewModel.toggleConfigServer(this@SettingsActivity)
                                if (result == getString(R.string.lan_config_no_wifi)) {
                                    Toast.makeText(this@SettingsActivity, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SettingsViewModel.MenuAction.LLM_CONFIG -> {
                                llmConfigLauncher.launch(Intent(this@SettingsActivity, LlmConfigActivity::class.java))
                            }
                            null -> {}
                            else -> {}
                        }
                        viewModel.clearMenuClickEvent()
                    }
                }
            }
        }
    }

    /**
     * Show unbind confirmation dialog
     */
    private fun showUnbindDialog(channelName: String, onUnbind: () -> Unit) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.unbind_title),
            message = getString(R.string.unbind_message, channelName, channelName),
            actionTitle = getString(R.string.unbind_action),
            onAction = onUnbind
        )
    }

    private fun showBudgetDialog() {
        val currentTokens = io.agents.arya.agent.TaskBudget.getConfiguredMaxTokens()
        val currentCost = io.agents.arya.agent.TaskBudget.getConfiguredMaxCost()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val tokenLabel = android.widget.TextView(this).apply {
            text = "Max tokens per task"
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(tokenLabel)

        val tokenOptions = arrayOf("Unlimited", "10K", "50K", "100K", "200K", "250K", "500K")
        val tokenValues = arrayOf<Int?>(null, 10_000, 50_000, 100_000, 200_000, 250_000, 500_000)
        val selectedTokenIndex = when (currentTokens) {
            null -> 0
            else -> tokenValues.indexOfFirst { it == currentTokens }.takeIf { it >= 0 }
                ?: tokenValues.indices
                    .filter { tokenValues[it] != null }
                    .minByOrNull { kotlin.math.abs((tokenValues[it] ?: 0) - currentTokens) }
                ?: 0
        }

        val tokenSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, tokenOptions)
            setSelection(selectedTokenIndex)
        }
        layout.addView(tokenSpinner)

        val costLabel = android.widget.TextView(this).apply {
            text = getString(R.string.settings_budget_max_cost)
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(costLabel)

        val costInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.settings_budget_no_cap_hint)
            setText(currentCost?.let { String.format("%.2f", it) } ?: "")
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(costInput)

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_task_budget))
            .setView(layout)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val newTokens = tokenValues[tokenSpinner.selectedItemPosition]
                val newCost = costInput.text.toString().trim().toDoubleOrNull()

                when (newTokens) {
                    null -> io.agents.arya.agent.TaskBudget.clearMaxTokens()
                    else -> io.agents.arya.agent.TaskBudget.saveMaxTokens(newTokens)
                }
                when {
                    newCost == null || newCost <= 0.0 -> io.agents.arya.agent.TaskBudget.clearMaxCost()
                    else -> io.agents.arya.agent.TaskBudget.saveMaxCost(newCost)
                }

                val summary = io.agents.arya.agent.TaskBudget.describeCurrentBudget()
                Toast.makeText(this, getString(R.string.settings_budget_toast, summary), Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }
}
