package io.agents.arya

import io.agents.arya.channel.Channel
import io.agents.arya.store.KeyValueStore
import io.agents.arya.store.MemoryKeyValueStore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TaskSessionSnapshot(
    val messageId: String = "",
    val channel: Channel? = null,
    val taskText: String = "",
    val autoReturnToChat: Boolean = true,
)

data class TaskSessionState(
    val messageId: String = "",
    val channel: Channel? = null,
    val autoReturnToChat: Boolean = true,
)

sealed interface TaskState {
    val taskId: String
    val startedAt: Long

    data class Idle(
        override val taskId: String = "",
        override val startedAt: Long = 0L,
    ) : TaskState

    data class Routing(
        override val taskId: String,
        override val startedAt: Long,
        val prompt: String,
    ) : TaskState

    data class Executing(
        override val taskId: String,
        override val startedAt: Long,
        val stepIndex: Int,
        val stepDescription: String,
    ) : TaskState

    data class ConfirmPending(
        override val taskId: String,
        override val startedAt: Long,
        val actionDescription: String,
        val toolName: String,
        val toolArgsJson: String,
    ) : TaskState

    data class Stopping(
        override val taskId: String,
        override val startedAt: Long,
    ) : TaskState

    data class Finished(
        override val taskId: String,
        override val startedAt: Long,
        val resultSummary: String,
    ) : TaskState

    data class Cancelled(
        override val taskId: String,
        override val startedAt: Long,
        val byUser: Boolean,
    ) : TaskState

    data class Failed(
        override val taskId: String,
        override val startedAt: Long,
        val reason: String,
        val lastStep: String?,
    ) : TaskState
}

fun TaskState.isTerminal(): Boolean =
    this is TaskState.Idle || this is TaskState.Finished ||
        this is TaskState.Cancelled || this is TaskState.Failed

data class TaskEventLog(
    val timestamp: Long,
    val type: String,
    val message: String,
)

sealed interface Transition {
    data class Start(val taskId: String, val prompt: String) : Transition
    data class Routed(val isTier1: Boolean) : Transition
    data class StepStarted(val stepIndex: Int, val description: String) : Transition
    data class StepCompleted(val stepIndex: Int, val description: String) : Transition
    data class RequireConfirm(val action: String, val toolName: String, val argsJson: String) : Transition
    data object ApproveConfirm : Transition
    data object DenyConfirm : Transition
    data class Finish(val summary: String) : Transition
    data class Error(val reason: String) : Transition
    data object RequestStop : Transition
    data object Stopped : Transition
}

class TaskSessionStore(
    private val kv: KeyValueStore = defaultStore(),
    @Suppress("unused") private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val debugTransitions: Boolean = false,
) {
    companion object {
        private const val KEY_SNAPSHOT = "task.snapshot"

        fun defaultStore(): KeyValueStore = try {
            val mmkvClass = Class.forName("com.tencent.mmkv.MMKV")
            val mmkv = mmkvClass.getMethod("defaultMMKV").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val encode = mmkvClass.getMethod("encode", String::class.java, String::class.java)
            val decode = mmkvClass.getMethod("decodeString", String::class.java, String::class.java)
            object : KeyValueStore {
                override fun encode(key: String, value: String) {
                    encode.invoke(mmkv, key, value)
                }
                override fun decode(key: String, default: String?): String? =
                    decode.invoke(mmkv, key, default) as String?
            }
        } catch (_: Throwable) {
            MemoryKeyValueStore()
        }
    }

    private val lock = Any()
    private val _state = MutableStateFlow<TaskState>(TaskState.Idle())
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TaskEventLog>(extraBufferCapacity = 64)
    val events: SharedFlow<TaskEventLog> = _events.asSharedFlow()

    private val eventHistory = mutableListOf<TaskEventLog>()
    @Volatile
    private var extra = TaskSessionSnapshot()

    init {
        restoreLastSnapshot()
    }

    fun snapshot(): TaskSessionSnapshot = extra
    fun isTaskRunning(): Boolean = !_state.value.isTerminal()

    fun tryAcquire(messageId: String, channel: Channel, taskText: String = ""): Boolean {
        return try {
            start(messageId, taskText)
            extra = TaskSessionSnapshot(messageId = messageId, channel = channel, taskText = taskText)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun release(): TaskSessionState {
        val snap = extra
        synchronized(lock) {
            if (!_state.value.isTerminal()) {
                apply(Transition.Finish("released"))
            }
        }
        extra = TaskSessionSnapshot()
        return TaskSessionState(snap.messageId, snap.channel, snap.autoReturnToChat)
    }

    fun markStopping(): Boolean {
        if (_state.value.isTerminal()) return false
        requestStop()
        return true
    }

    fun updateTaskText(taskText: String) {
        extra = extra.copy(taskText = taskText)
    }

    fun start(taskId: String, prompt: String) {
        synchronized(lock) {
            val current = _state.value
            if (!current.isTerminal()) {
                throw IllegalStateException("Cannot start while ${current.javaClass.simpleName}")
            }
            eventHistory.clear()
            apply(Transition.Start(taskId, prompt))
        }
    }

    fun transition(t: Transition) {
        synchronized(lock) { apply(t) }
    }

    fun requestStop() {
        synchronized(lock) {
            val current = _state.value
            if (!current.isTerminal() && current !is TaskState.Stopping) {
                apply(Transition.RequestStop)
                appendEventLocked("STOP", "Stop requested")
            }
        }
    }

    fun appendEvent(type: String, message: String) {
        synchronized(lock) { appendEventLocked(type, message) }
    }

    private fun appendEventLocked(type: String, message: String) {
        val log = TaskEventLog(System.currentTimeMillis(), type, message)
        eventHistory.add(log)
        if (eventHistory.size > 20) eventHistory.removeAt(0)
        _events.tryEmit(log)
        persistSnapshot(_state.value)
    }

    private fun apply(t: Transition) {
        val current = _state.value
        val next = computeNextState(current, t)
        if (next === current || next == current) {
            if (debugTransitions && t !is Transition.Start) {
                throw IllegalStateException("Illegal transition ${t::class.simpleName} from ${current::class.simpleName}")
            }
            return
        }
        _state.value = next
        persistSnapshot(next)
    }

    internal fun computeNextState(current: TaskState, t: Transition): TaskState {
        return when (t) {
            is Transition.Start -> TaskState.Routing(t.taskId, System.currentTimeMillis(), t.prompt)
            is Transition.Routed -> when (current) {
                is TaskState.Routing -> if (t.isTier1) {
                    TaskState.Finished(current.taskId, current.startedAt, "Done (Tier1)")
                } else {
                    TaskState.Executing(current.taskId, current.startedAt, 0, "Running")
                }
                else -> current
            }
            is Transition.StepStarted -> when (current) {
                is TaskState.Executing, is TaskState.Routing ->
                    TaskState.Executing(current.taskId, current.startedAt, t.stepIndex, t.description)
                else -> current
            }
            is Transition.StepCompleted -> when (current) {
                is TaskState.Executing ->
                    TaskState.Executing(current.taskId, current.startedAt, t.stepIndex + 1, t.description)
                else -> current
            }
            is Transition.RequireConfirm -> when (current) {
                is TaskState.Executing ->
                    TaskState.ConfirmPending(current.taskId, current.startedAt, t.action, t.toolName, t.argsJson)
                else -> current
            }
            is Transition.ApproveConfirm -> when (current) {
                is TaskState.ConfirmPending ->
                    TaskState.Executing(current.taskId, current.startedAt, 0, "Continue after confirm")
                else -> current
            }
            is Transition.DenyConfirm -> when (current) {
                is TaskState.ConfirmPending ->
                    TaskState.Cancelled(current.taskId, current.startedAt, byUser = true)
                else -> current
            }
            is Transition.Finish -> if (!current.isTerminal() || current is TaskState.Idle) {
                TaskState.Finished(current.taskId, current.startedAt, t.summary)
            } else current
            is Transition.Error -> if (!current.isTerminal()) {
                TaskState.Failed(
                    current.taskId,
                    current.startedAt,
                    t.reason,
                    (current as? TaskState.Executing)?.stepDescription,
                )
            } else current
            is Transition.RequestStop -> if (!current.isTerminal()) {
                TaskState.Stopping(current.taskId, current.startedAt)
            } else current
            is Transition.Stopped -> if (current is TaskState.Stopping || !current.isTerminal()) {
                TaskState.Cancelled(current.taskId, current.startedAt, byUser = true)
            } else current
        }
    }

    private fun persistSnapshot(state: TaskState) {
        val json = JSONObject().apply {
            put("type", state.javaClass.simpleName)
            put("taskId", state.taskId)
            put("startedAt", state.startedAt)
            put("isTerminal", state.isTerminal())
            when (state) {
                is TaskState.Routing -> put("prompt", state.prompt)
                is TaskState.Executing -> {
                    put("stepIndex", state.stepIndex)
                    put("stepDescription", state.stepDescription)
                }
                is TaskState.ConfirmPending -> {
                    put("actionDescription", state.actionDescription)
                    put("toolName", state.toolName)
                    put("toolArgsJson", state.toolArgsJson)
                }
                is TaskState.Finished -> put("resultSummary", state.resultSummary)
                is TaskState.Cancelled -> put("byUser", state.byUser)
                is TaskState.Failed -> {
                    put("reason", state.reason)
                    put("lastStep", state.lastStep ?: "")
                }
                else -> {}
            }
            val eventsArray = JSONArray()
            for (ev in eventHistory) {
                eventsArray.put(JSONObject().apply {
                    put("ts", ev.timestamp)
                    put("type", ev.type)
                    put("msg", ev.message)
                })
            }
            put("events", eventsArray)
        }
        kv.encode(KEY_SNAPSHOT, json.toString())
    }

    private fun restoreLastSnapshot() {
        val jsonStr = kv.decode(KEY_SNAPSHOT, null) ?: return
        try {
            val json = JSONObject(jsonStr)
            val isTerminal = json.optBoolean("isTerminal", true)
            val taskId = json.optString("taskId", "")
            val startedAt = json.optLong("startedAt", 0L)
            if (!isTerminal && taskId.isNotEmpty()) {
                val restored = TaskState.Failed(
                    taskId = taskId,
                    startedAt = startedAt,
                    reason = "Previous task stopped because the app was closed",
                    lastStep = json.optString("stepDescription", ""),
                )
                _state.value = restored
                persistSnapshot(restored)
            }
        } catch (_: Exception) {
        }
    }
}
