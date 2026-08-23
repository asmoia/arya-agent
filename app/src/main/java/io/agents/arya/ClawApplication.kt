package io.agents.arya

import io.agents.arya.agent.DefaultAgentService
import io.agents.arya.engine.EngineClient
import io.agents.arya.agent.llm.InferenceTelemetryCollector
import io.agents.arya.base.BaseApp
import io.agents.arya.channel.ChannelManager
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.utils.AppLogStore
import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog
import com.blankj.utilcode.util.NetworkUtils

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }

class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel
    }

    lateinit var engineClient: EngineClient
        private set
    lateinit var taskSessionStore: TaskSessionStore
        private set
    lateinit var permissionTruth: PermissionTruth
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCapabilityCoordinator.markProcessStart()
        AppLogStore.init(this)
        XLog.setDEBUG(BuildConfig.DEBUG)

        KVUtils.init(this)
        try {
            val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()
            if (mmkv.decodeInt("schema_version", 0) < 2) {
                mmkv.encode("schema_version", 2)
            }
        } catch (_: Exception) {
        }

        // Singletons for redesign architecture
        engineClient = EngineClient(this)
        taskSessionStore = TaskSessionStore()
        permissionTruth = PermissionTruth(this)

        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]

        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        io.agents.arya.agent.skill.SkillRegistry.loadBuiltInSkills()
        io.agents.arya.agent.PlaybookManager.loadAll(this)

        // Hermes recovery/cron archived (S8).

        DefaultAgentService.FILE_LOGGING_ENABLED = true
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        appViewModelInstance.initCommon()
        Thread({
            try {
                if (KVUtils.hasLlmConfig()) {
                    appViewModelInstance.initAgent()
                    appViewModelInstance.afterInit()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Async init failed: ${e.message}", e)
            }
        }, "app-async-init").start()
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        ChannelManager.reconnectIfNeeded()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "Network disconnected")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }
}
