package io.agents.arya

import com.tencent.mmkv.MMKV
import io.agents.arya.channel.Channel
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
        override val startedAt: Long = 0L
    ) : TaskState

    data class Routing(
        override val taskId: String,
        override val startedAt: Long,
        val prompt: String
    ) : TaskState

    data class Executing(
        override val taskId: String,
        override val startedAt: Long,
        val stepIndex: Int,
        val stepDescription: String
    ) : TaskState

    data class ConfirmPending(
        override val taskId: String,
        override val startedAt: Long,
        val actionDescription: String,
        val toolName: String,
        val toolArgsJson: String
    ) : TaskState

    data class Stopping(
        override val taskId: String,
        override val startedAt: Long
    ) : TaskState

    data class Finished(
        override val taskId: String,
        override val startedAt: Long,
        val resultSummary: String
    ) : TaskState

    data class Cancelled(
        override val taskId: String,
        override val startedAt: Long,
        val byUser: Boolean
    ) : TaskState

    data class Failed(
        override val taskId: String,
        override val startedAt: Long,
        val reason: String,
        val lastStep: String?
    ) : TaskState
}

fun TaskState.isTerminal(): Boolean {
    return this is TaskState.Idle ||
           this is TaskState.Finished ||
           this is TaskState.Cancelled ||
           this is TaskState.Failed
}

data class TaskEventLog(
    val timestamp: Long,
    val type: String,
    val message: String
)

sealed interface Transition {
    data class Start(val taskId: String, val prompt: String) : Transition
    data class Routed(val isTier1: Boolean) : Transition
    data class StepStarted(val stepIndex: Int, val description: String) : Transition
    data class StepCompleted(val stepIndex: Int, val description: String) : Transition
    data class RequireConfirm(val action: String, val toolName: String, val argsJson: String) : Transition
    object ApproveConfirm : Transition
    object DenyConfirm : Transition
    data class Finish(val summary: String) : Transition
    data class Error(val reason: String) : Transition
    object RequestStop : Transition
    object Stopped : Transition
}

class TaskSessionStore(
    private val mmkv: MMKV = MMKV.defaultMMKV(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val KEY_SNAPSHOT = "task.snapshot"
    }

    private val lock = Any()
    private val _state = MutableStateFlow<TaskState>(TaskState.Idle())
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TaskEventLog>(extraBufferCapacity = 64)
    val events: SharedFlow<TaskEventLog> = _events.asSharedFlow()

    private val eventHistory = mutableListOf<TaskEventLog>()

    init {
        restoreLastSnapshot()
    }

    fun start(taskId: String, prompt: String) {
        synchronized(lock) {
            val current = _state.value
            if (!current.isTerminal()) {
                throw IllegalStateException("Cannot start new task while current task is non-terminal: ${current.javaClass.simpleName}")
            }
            eventHistory.clear()
            val newState = TaskState.Routing(taskId = taskId, startedAt = System.currentTimeMillis(), prompt = prompt)
            _state.value = newState
            persistSnapshot(newState)
        }
    }

    fun transition(t: Transition) {
        synchronized(lock) {
            val current = _state.value
            val nextState = computeNextState(current, t)
            if (nextState != current) {
                _state.value = nextState
                persistSnapshot(nextState)
            }
        }
    }

    fun requestStop() {
        synchronized(lock) {
            val current = _state.value
            if (!current.isTerminal() && current !is TaskState.Stopping) {
                val newState = TaskState.Stopping(taskId = current.taskId, startedAt = current.startedAt)
                _state.value = newState
                persistSnapshot(newState)
                appendEvent("STOP", "کاربر درخواست توقف داد")
            }
        }
    }

    fun appendEvent(type: String, message: String) {
        synchronized(lock) {
            val log = TaskEventLog(System.currentTimeMillis(), type, message)
            eventHistory.add(log)
            if (eventHistory.size > 20) {
                eventHistory.removeAt(0)
            }
            _events.tryEmit(log)
            persistSnapshot(_state.value)
        }
    }

    private fun computeNextState(current: TaskState, t: Transition): TaskState {
        return when (t) {
            is Transition.Start -> TaskState.Routing(t.taskId, System.currentTimeMillis(), t.prompt)
            is Transition.Routed -> {
                if (current is TaskState.Routing) {
                    if (t.isTier1) {
                        TaskState.Finished(current.taskId, current.startedAt, "انجام شد (Tier1)")
                    } else {
                        TaskState.Executing(current.taskId, current.startedAt, stepIndex = 0, stepDescription = "در حال اجرا")
                    }
                } else current
            }
            is Transition.StepStarted -> {
                if (current is TaskState.Executing || current is TaskState.Routing) {
                    TaskState.Executing(current.taskId, current.startedAt, t.stepIndex, t.description)
                } else current
            }
            is Transition.StepCompleted -> {
                if (current is TaskState.Executing) {
                    TaskState.Executing(current.taskId, current.startedAt, t.stepIndex + 1, t.description)
                } else current
            }
            is Transition.RequireConfirm -> {
                if (current is TaskState.Executing) {
                    TaskState.ConfirmPending(current.taskId, current.startedAt, t.action, t.toolName, t.argsJson)
                } else current
            }
            is Transition.ApproveConfirm -> {
                if (current is TaskState.ConfirmPending) {
                    TaskState.Executing(current.taskId, current.startedAt, stepIndex = 0, stepDescription = "ادامه اجرا پس از تایید")
                } else current
            }
            is Transition.DenyConfirm -> {
                if (current is TaskState.ConfirmPending) {
                    TaskState.Cancelled(current.taskId, current.startedAt, byUser = true)
                } else current
            }
            is Transition.Finish -> {
                TaskState.Finished(current.taskId, current.startedAt, t.summary)
            }
            is Transition.Error -> {
                TaskState.Failed(current.taskId, current.startedAt, t.reason, (current as? TaskState.Executing)?.stepDescription)
            }
            is Transition.RequestStop -> {
                if (!current.isTerminal()) {
                    TaskState.Stopping(current.taskId, current.startedAt)
                } else current
            }
            is Transition.Stopped -> {
                if (current is TaskState.Stopping || !current.isTerminal()) {
                    TaskState.Cancelled(current.taskId, current.startedAt, byUser = true)
                } else current
            }
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
        mmkv.encode(KEY_SNAPSHOT, json.toString())
    }

    private fun restoreLastSnapshot() {
        val jsonStr = mmkv.decodeString(KEY_SNAPSHOT, null) ?: return
        try {
            val json = JSONObject(jsonStr)
            val isTerminal = json.optBoolean("isTerminal", true)
            val taskId = json.optString("taskId", "")
            val startedAt = json.optLong("startedAt", 0L)

            if (!isTerminal && taskId.isNotEmpty()) {
                val restoredState = TaskState.Failed(
                    taskId = taskId,
                    startedAt = startedAt,
                    reason = "کار قبلی به دلیل بسته شدن برنامه متوقف شد",
                    lastStep = json.optString("stepDescription", "")
                )
                _state.value = restoredState
                persistSnapshot(restoredState)
            }
        } catch (_: Exception) {}
    }
}
