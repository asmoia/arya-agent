// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.support

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.BuildConfig
import io.agents.arya.agent.llm.LocalBackendHealth
import io.agents.arya.agent.llm.LocalModelManager
import io.agents.arya.agent.llm.LocalInferenceCoordinator
import io.agents.arya.agent.llm.ModelConfigRepository
import io.agents.arya.agent.TaskPerfTrace
import io.agents.arya.service.AutoReplyManager
import io.agents.arya.utils.AppLogStore
import io.agents.arya.utils.KVUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugReportManager {

    private const val REPORT_DIR = "debug_reports"
    private const val LOGCAT_LINES = "2500"
    private const val MAX_HTTP_LOGS = 5

    fun buildReport(context: Context): File {
        val reportDir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(reportDir, "arya-debug-$timestamp.zip")

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addText(zip, "summary.txt", buildSummary(context))
            addText(zip, "process-exit.txt", io.agents.arya.engine.ProcessExitDump.dumpText(context))
            addText(zip, "bug-report-template.txt", buildBugReportTemplate(context))
            collectLogcat().takeIf { it.isNotBlank() }?.let { addText(zip, "app-logcat.txt", it) }
            collectLogcatCrashBuffer().takeIf { it.isNotBlank() }?.let { addText(zip, "crash-buffer.txt", it) }
            collectProcSnippets().takeIf { it.isNotBlank() }?.let { addText(zip, "proc-snippets.txt", it) }
            addRecentAppLogs(zip, context)
            addEngineLogs(zip, context)
            addRecentHttpLogs(zip, context.cacheDir)
        }

        return output
    }

    private fun buildSummary(context: Context): String {
        val capabilities = AppCapabilityCoordinator.snapshot(context)
        val config = ModelConfigRepository.snapshot()
        val httpDir = File(context.cacheDir, "http_logs")
        val httpLogs = httpDir.listFiles()?.size ?: 0
        val appLogs = AppLogStore.listLogFiles(context).size
        val modelStorage = LocalModelManager.storageDiagnostics(context)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val runtimeMemory = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
        val localInference = LocalInferenceCoordinator.snapshot()
        val autoReplyManager = AutoReplyManager.getInstance()
        val monitorTargets = autoReplyManager.monitoredTargets.joinToString(", ") { it.displayLabel }
        val cpuSafeAt = KVUtils.getLocalCpuSafeAt()
        val gpuVerifiedAt = KVUtils.getLocalGpuVerifiedAt()
        val gpuRearmEligible = LocalBackendHealth.shouldRearmVerifiedGpu(
            isCpuSafeModeEnabled = LocalBackendHealth.isCpuSafeModeEnabled(),
            hasVerifiedGpuSuccess = LocalBackendHealth.hasVerifiedGpuSuccess(),
            hasPendingGpuInitMarker = LocalBackendHealth.hasPendingGpuInitMarker(),
            cpuSafeReason = LocalBackendHealth.cpuSafeReason(),
            cpuSafeAtMs = cpuSafeAt,
            nowMs = System.currentTimeMillis(),
        )
        return buildString {
            appendLine("Arya Debug Report")
            appendLine("Generated: ${Date()}")
            appendLine()
            appendLine("LAST ENGINE DEATH (read this first)")
            appendLine(lastEngineDeathBlock(context))
            appendLine()
            appendLine("App")
            appendLine("- Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- Debug build: ${BuildConfig.DEBUG}")
            appendLine("- Package: ${BuildConfig.APPLICATION_ID}")
            appendLine()
            appendLine("Device")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Model: ${Build.MODEL}")
            appendLine("- Device: ${Build.DEVICE}")
            appendLine("- Hardware: ${Build.HARDWARE}")
            appendLine("- Fingerprint: ${Build.FINGERPRINT}")
            appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("- RAM (total): ${getDeviceRamGb(context)} GB")
            appendLine("- RAM (available at report): ${formatMb(runtimeMemory.availMem)}")
            appendLine("- Low-memory threshold: ${formatMb(runtimeMemory.threshold)}")
            appendLine("- App memoryClass: ${activityManager?.memoryClass ?: 0} MB")
            appendLine("- App largeMemoryClass: ${activityManager?.largeMemoryClass ?: 0} MB")
            appendLine()
            appendLine("Local Inference Runtime (#41 / #14 diagnostics)")
            appendLine("- Conversation lease: owner=${localInference.owner}, phase=${localInference.phase}, backend=${localInference.backend ?: "-"}, generation=${localInference.generation}")
            appendLine("- Active lease failure: ${localInference.failure ?: "(none)"}")
            appendLine("- Last local runtime failure: ${localInference.lastFailure ?: "(none)"}")
            if (LocalModelManager.isRetiredHeavyLocalModel(config.local.modelPath)) {
                appendLine("- Retired large local model: disabled by Fast Local policy")
            } else {
                appendLine("- Fast Local: deterministic actions do not require a model load")
            }
            val openClPaths = detectOpenClLibraryPaths()
            appendLine("- OpenCL libraries found: ${if (openClPaths.isEmpty()) "(none) — GPU path will not work" else openClPaths.joinToString(", ")}")
            appendLine("- Backend health: ${LocalBackendHealth.debugStateSummary()}")
            appendLine()
            appendLine("Capabilities")
            appendLine("- Accessibility: ${capabilities.accessibilityStatusLabel}")
            appendLine("- Notification access: ${capabilities.notificationAccessStatusLabel}")
            appendLine("- Notification permission: ${capabilities.notificationPermissionStatusLabel}")
            appendLine("- Overlay: ${if (capabilities.overlayGranted) "Enabled" else "Disabled"}")
            appendLine("- Battery optimization: ${if (capabilities.batteryOptimizationIgnored) "Unrestricted" else "Restricted"}")
            appendLine("- Foreground service: ${if (capabilities.foregroundServiceRunning) "Running" else "Stopped"}")
            appendLine("- Accessibility last connected: ${formatEpoch(KVUtils.getAccessibilityLastConnectedAt())}")
            appendLine("- Accessibility last heartbeat: ${formatEpoch(KVUtils.getAccessibilityLastHeartbeatAt())}")
            appendLine("- Accessibility last interrupted: ${formatEpoch(KVUtils.getAccessibilityLastInterruptedAt())}")
            appendLine("- Accessibility last disconnected: ${formatEpoch(KVUtils.getAccessibilityLastDisconnectedAt())}")
            appendLine("- Notification listener last connected: ${formatEpoch(KVUtils.getNotificationListenerLastConnectedAt())}")
            appendLine("- Notification listener last disconnected: ${formatEpoch(KVUtils.getNotificationListenerLastDisconnectedAt())}")
            appendLine()
            appendLine("LLM")
            appendLine("- Active mode: ${config.activeMode}")
            appendLine("- Active cloud model: ${config.activeCloud.modelName.ifBlank { "(none)" }}")
            appendLine("- Default local model: ${config.local.displayName.ifBlank { "(none)" }}")
            appendLine("- Local path: ${config.local.modelPath.ifBlank { "(none)" }}")
            appendLine("- Local backend preference: ${config.local.backendPreference.ifBlank { "(default)" }}")
            appendLine("- Local backend device key: ${LocalBackendHealth.currentDeviceKey()}")
            appendLine("- Local backend device descriptor: ${LocalBackendHealth.debugDeviceDescriptor()}")
            appendLine("- CPU-safe mode: ${if (LocalBackendHealth.isCpuSafeModeEnabled()) "Enabled" else "Disabled"}")
            appendLine("- CPU-safe reason: ${LocalBackendHealth.cpuSafeReason().ifBlank { "(none)" }}")
            appendLine("- CPU-safe set at: ${formatEpoch(cpuSafeAt)}")
            appendLine("- Conservative CPU-first suggested: ${if (LocalBackendHealth.isConservativeCpuModeSuggested()) "Yes" else "No"}")
            appendLine("- GPU already verified healthy: ${if (LocalBackendHealth.hasVerifiedGpuSuccess()) "Yes" else "No"}")
            appendLine("- GPU verified at: ${formatEpoch(gpuVerifiedAt)}")
            appendLine("- GPU re-arm eligible now: ${if (gpuRearmEligible) "Yes" else "No"}")
            appendLine("- Pending GPU init marker: ${if (LocalBackendHealth.hasPendingGpuInitMarker()) "Present" else "None"}")
            appendLine()
            appendLine("Local model storage")
            appendLine("- Selected model dir: ${modelStorage.selectedDir ?: "(none)"}")
            appendLine("- Selected model dir available bytes: ${modelStorage.selectedAvailableBytes?.toString() ?: "(unknown)"}")
            appendLine("- Selected model dir error: ${modelStorage.selectedError ?: "(none)"}")
            appendLine("- External model dir: ${modelStorage.externalDir}")
            appendLine("- External model dir status: ${modelStorage.externalStatus}")
            appendLine("- Internal model dir: ${modelStorage.internalDir}")
            appendLine("- Internal model dir status: ${modelStorage.internalStatus}")
            appendLine()
            appendLine("Auto-reply")
            appendLine("- Enabled: ${if (autoReplyManager.isEnabled) "Yes" else "No"}")
            appendLine("- Targets: ${monitorTargets.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Recent task performance traces")
            val traces = TaskPerfTrace.recent().takeLast(8)
            if (traces.isEmpty()) {
                appendLine("- (none)")
            } else {
                traces.forEach { trace ->
                    val firstTool = trace.events.firstOrNull { it.name == "tool_requested" }?.elapsedMs
                    appendLine("- ${trace.route} outcome=${trace.outcome} firstTool=${firstTool ?: "-"}ms events=${trace.events.size}")
                }
            }
            appendLine()
            appendLine("Artifacts")
            appendLine("- App rolling logs present: $appLogs")
            appendLine("- Engine rolling logs present: ${io.agents.arya.engine.EngineLog.listFiles(context).size}")
            appendLine("- HTTP log files present: $httpLogs")
        }
    }

    private fun buildBugReportTemplate(context: Context): String {
        return buildString {
            appendLine("Arya Bug Report Template")
            appendLine()
            appendLine("Attach this ZIP and fill in the blanks below:")
            appendLine()
            appendLine("- What happened:")
            appendLine("- What you expected instead:")
            appendLine("- Exact steps to reproduce:")
            appendLine("- Does it happen every time, or only sometimes?")
            appendLine("- Device model + ROM/build:")
            appendLine("- App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("If this looks device-specific and you have ADB available, add:")
            appendLine("adb logcat -c")
            appendLine("adb logcat -v time > arya-logcat.txt")
            appendLine("# reproduce once, then stop with Ctrl+C")
            appendLine("adb shell dumpsys activity top > arya-activity-top.txt")
            appendLine("adb shell dumpsys activity services io.agents.arya > arya-services.txt")
            appendLine()
            appendLine("Open a new GitHub issue: https://github.com/asmoia/arya-agent/issues/new")
            appendLine("Built on: ${Date()}")
            appendLine("Package: ${context.packageName}")
        }
    }

    private fun lastEngineDeathBlock(context: Context): String {
        val dir = File(context.cacheDir, "engine_logs")
        fun tail(name: String, n: Int): String {
            val f = File(dir, name)
            if (!f.isFile) return "(missing $name)"
            return runCatching { f.readText().takeLast(n).trim() }.getOrDefault("(read failed $name)")
        }
        return buildString {
            appendLine("- heartbeat: ${tail("native-heartbeat.txt", 400)}")
            appendLine("- crash: ${tail("native-crash.txt", 600)}")
            appendLine("- last stages: ${tail("native-load-stage.txt", 700).replace("\n", " ; ")}")
            appendLine("- process-exit (API 30+):")
            appendLine(io.agents.arya.engine.ProcessExitDump.dumpText(context).take(1200))
        }
    }

    private fun collectLogcat(): String {
        return runCatching {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                LOGCAT_LINES,
                "ClawA11yService:V",
                "ClawNotifListener:V",
                "AutoReplyManager:V",
                "ForegroundService:V",
                "LocalBackendHealth:V",
                "LocalModelManager:V",
                "EngineHolder:V",
                "AryaEngineLog:V",
                "AryaEngineJNI:V",
                "AryaLlama:V",
                "EngineService:V",
                "EngineClient:V",
                "LocalLlmClient:V",
                "LocalModelRuntime:V",
                "LocalInferenceCoordinator:V",
                "ChatSessionController:V",
                "InputTextTool:V",
                "SendMessageTool:V",
                "DEBUG:V",
                "libc:I",
                "tombstoned:I",
                "ActivityManager:I",
                "am_proc_died:I",
                "am_kill:I",
                "am_crash:I",
                "lmkd:V",
                "AndroidRuntime:V",
                "Zygote:I",
                "HwSystemManager:I",
                "AwareLog:I",
                "*:E",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "Failed to collect logcat: ${it.message}" }
    }

    private fun collectLogcatCrashBuffer(): String {
        return runCatching {
            val process = ProcessBuilder(
                "logcat", "-d", "-b", "crash", "-v", "threadtime", "-t", "200",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "Failed to collect crash buffer: ${it.message}" }
    }

    private fun collectProcSnippets(): String {
        return buildString {
            appendLine("=== /proc/cpuinfo ===")
            appendLine(readCappedFile("/proc/cpuinfo", 12_000))
            appendLine("=== cpu present/possible ===")
            appendLine(readCappedFile("/sys/devices/system/cpu/present", 128))
            appendLine(readCappedFile("/sys/devices/system/cpu/possible", 128))
            for (i in 0 until 16) {
                val freq = readCappedFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq", 64).trim()
                if (freq.isNotBlank()) appendLine("cpu$i max_khz=$freq")
            }
            appendLine("=== getprop subset ===")
            appendLine(runCatching {
                val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence()
                        .filter { line ->
                            listOf("ro.hardware", "ro.board", "ro.product", "ro.build.fingerprint",
                                "ro.huawei", "persist.sys", "ro.arch", "ro.soc").any { line.contains(it) }
                        }
                        .take(80)
                        .joinToString("\n")
                }
            }.getOrElse { "getprop failed: ${it.message}" })
        }
    }

    private fun readCappedFile(path: String, cap: Int): String {
        return runCatching {
            File(path).takeIf { it.isFile }?.readText()?.take(cap) ?: "(missing $path)"
        }.getOrDefault("(read failed $path)")
    }

    private fun addRecentHttpLogs(zip: ZipOutputStream, cacheDir: File) {
        val httpDir = File(cacheDir, "http_logs")
        val files = httpDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_HTTP_LOGS)
            ?: return
        for (file in files) {
            addFile(zip, "http_logs/${file.name}", file)
        }
    }

    private fun addRecentAppLogs(zip: ZipOutputStream, context: Context) {
        val files = AppLogStore.listLogFiles(context)
        for (file in files) {
            addFile(zip, "app_logs/${file.name}", file)
        }
    }

    private fun addEngineLogs(zip: ZipOutputStream, context: Context) {
        val files = io.agents.arya.engine.EngineLog.listFiles(context)
        for (file in files) {
            addFile(zip, "engine_logs/${file.name}", file)
        }
    }

    private fun addText(zip: ZipOutputStream, entryName: String, content: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        if (!file.exists() || !file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun formatEpoch(value: Long): String {
        if (value <= 0L) return "(none)"
        return Date(value).toString()
    }

    private fun formatMb(bytes: Long): String {
        if (bytes <= 0L) return "(unknown)"
        return String.format(Locale.US, "%.1f GB", bytes / (1000.0 * 1000.0 * 1000.0))
    }

    /** Returns total device RAM in GB (rounded up). Used in debug summary for GPU/model RAM
     *  sizing diagnostics (#41 / #14 — OEM bug reporters need to know real RAM vs minRamGb). */
    private fun getDeviceRamGb(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
        } catch (_: Throwable) {
            -1
        }
    }

    /** Probes well-known Android OpenCL driver paths. If none exist the GPU LiteRT path will
     *  fail at first inference (this is the root cause for issue #14 and the Pixel-8-Pro
     *  GPU→CPU fallback documented in #41). Returning the actual paths found makes triage
     *  trivial for non-Pixel OEM reporters. */
    private fun detectOpenClLibraryPaths(): List<String> {
        val candidates = listOf(
            "/system/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so",
        )
        return candidates.filter {
            runCatching { File(it).exists() }.getOrDefault(false)
        }
    }
}
