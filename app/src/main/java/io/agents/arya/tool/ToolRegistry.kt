// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.tool

import io.agents.arya.agent.knowledge.*
import io.agents.arya.tool.impl.*
import io.agents.arya.tool.impl.mobile.*
import io.agents.arya.tool.impl.tv.*

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = LinkedHashMap<String, BaseTool>()
    var deviceType: DeviceType = DeviceType.TV
        private set

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
        io.agents.arya.agent.langchain.LangChain4jToolBridge.invalidateCache()
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(WaitForUiTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(GetDeviceInfoTool())
        register(GetNotificationsTool())
        register(MakeCallTool())
        register(FinishTool())
        // Knowledge Base tools — shared vault available in all modes
        register(KbWriteTool())
        register(KbReadTool())
        register(KbSearchTool())
        register(KbAppendTool())
        register(KbAddTodoTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(TapNodeTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(FindAndTapTool())
        register(SendMessageTool())
        register(AutoReplyTool())
        // Arya-specific tools: EMUI settings + Shamsi calendar
        register(EmuiSettingsTool())
        register(ShamsiCalendarTool())
        // Background listen for messaging/voice notifications (Hermes)
        register(HermesListenVoiceTool())
        // Bounded no-LLM shortcuts for common phone tasks.
        register(TelegramSavedMediaTool())
        register(SearchBrowserTool())
        register(OpenMessagingChatTool())
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
        io.agents.arya.agent.langchain.LangChain4jToolBridge.invalidateCache()
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            // Safety gate: ask the user before high-risk phone actions.
            // Applies to both HermesAgentService and DefaultAgentService paths.
            val deny = io.agents.arya.agent.hermes.safety.SensitiveActionGate.awaitApproval(name, params)
            if (deny != null) {
                io.agents.arya.utils.XLog.w("ToolRegistry", "Sensitive action blocked: $name — $deny")
                return ToolResult.error(deny)
            }
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            io.agents.arya.utils.XLog.e("ToolRegistry", "Tool '$name' execution failed with params=$params", e)
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }
}
