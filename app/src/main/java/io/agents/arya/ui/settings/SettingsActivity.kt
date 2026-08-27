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
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agents.arya.R
import io.agents.arya.base.BaseActivity
import io.agents.arya.widget.AlertDialog
import io.agents.arya.widget.ConfirmDialog
import io.agents.arya.widget.CommonToolbar
import io.agents.arya.widget.MenuGroup
import io.agents.arya.widget.MenuItem
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.AppRequirement
import io.agents.arya.appViewModel
import io.agents.arya.server.ConfigServerManager
import io.agents.arya.service.ForegroundService
import io.agents.arya.support.DebugReportManager
import io.agents.arya.utils.KVUtils
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
    private var sensitiveConfirmItem: io.agents.arya.widget.MenuItem? = null

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
        window.navigationBarColor = themeColors.bg
        window.decorView.systemUiVisibility = if (io.agents.arya.ui.chat.ThemeManager.isDark()) {
            0
        } else {
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
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
        styleSearchBox(themeColors)
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
        show(R.id.aboutGroup, SettingsGroup.ADVANCED)
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        refreshPermissions()
        refreshSafety()
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

    private fun refreshSafety() {
        sensitiveConfirmItem?.setTrailingText(
            if (KVUtils.isSensitiveConfirmEnabled()) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled)
        )
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
                    refreshSafety()
                    Toast.makeText(this, R.string.settings_sensitive_disabled, Toast.LENGTH_LONG).show()
                }
            )
        } else {
            KVUtils.setSensitiveConfirmEnabled(true)
            refreshSafety()
            Toast.makeText(this, R.string.settings_sensitive_enabled, Toast.LENGTH_SHORT).show()
        }
    }


    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun styleSearchBox(tc: io.agents.arya.ui.chat.ThemeManager.ChatColors) {
        findViewById<android.widget.EditText>(R.id.settingsSearch)?.apply {
            setTextColor(tc.aiText)
            setHintTextColor(tc.toolDefault)
            background = GradientDrawable().apply {
                setColor(tc.toolbarBg)
                setStroke(dp(1), tc.inputBorder)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(48)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyThemeToGroups(tc: io.agents.arya.ui.chat.ThemeManager.ChatColors) {
        val groups = listOf(
            R.id.permissionsGroup, R.id.channelGroup, R.id.modelGroup,
            R.id.appearanceGroup, R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup
        )
        for (id in groups) {
            val g = findViewById<MenuGroup>(id) ?: continue
            g.setTitleColor(tc.aiText)
            g.setCardBackgroundColor(tc.aiBubble)
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

        // Assistant behavior: keep only the controls users need day to day.
        val toolsGroup = findViewById<MenuGroup>(R.id.toolsGroup)
        toolsGroup.setTitle(getString(R.string.settings_group_assistant))

        sensitiveConfirmItem = toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = getString(R.string.settings_sensitive_confirm),
            onClick = { toggleSensitiveConfirm() },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isSensitiveConfirmEnabled()) getString(R.string.settings_status_enabled) else getString(R.string.settings_status_disabled))
        }

        toolsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_rocket,
            title = getString(R.string.settings_voice_auto_send),
            onClick = {
                val next = !KVUtils.isVoiceAutoSend()
                KVUtils.setVoiceAutoSend(next)
                Toast.makeText(
                    this,
                    if (next) getString(R.string.settings_voice_auto_send_enabled)
                    else getString(R.string.settings_voice_auto_send_disabled),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isVoiceAutoSend()) "ON" else "OFF")
        }

        toolsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.settings_voice_tts),
            onClick = {
                val next = !KVUtils.isVoiceTtsEnabled()
                KVUtils.setVoiceTtsEnabled(next)
                Toast.makeText(
                    this,
                    if (next) getString(R.string.settings_voice_tts_enabled)
                    else getString(R.string.settings_voice_tts_disabled),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(if (KVUtils.isVoiceTtsEnabled()) "ON" else "OFF")
        }

        // Remote automation is still supported internally, but stays out of the
        // primary Settings surface until it has a dedicated security screen.
        findViewById<MenuGroup>(R.id.remoteGroup)?.visibility = android.view.View.GONE

        // About
        val aboutGroup = findViewById<MenuGroup>(R.id.aboutGroup)
        aboutGroup.setTitle(getString(R.string.settings_about))

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = getString(R.string.app_name),
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText("v${io.agents.arya.BuildConfig.VERSION_NAME}")
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
            showDivider = false
        ).apply {
            setTrailingText(getString(R.string.settings_zip_logs_state))
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
