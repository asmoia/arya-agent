package io.agents.arya.ui.chat

import android.app.Activity
import io.agents.arya.ClawApplication
import io.agents.arya.TaskEvent
import io.agents.arya.engine.EngineLog

object ChatDispatch {
    fun sendPhoneAction(activity: Activity, runtime: ChatRuntime, text: String) {
        runtime.ingestUser(text)
        val taskId = "chat_${System.currentTimeMillis()}"
        EngineLog.breadcrumb("ChatDispatch", "phone-action text='${text.take(80)}'")
        ClawApplication.appViewModelInstance.startTask(text, taskId) { event ->
            activity.runOnUiThread {
                when (event) {
                    is TaskEvent.Completed -> runtime.finishAssistant(event.answer.ifBlank { "انجام شد." })
                    is TaskEvent.Failed -> runtime.finishAssistant(event.error)
                    TaskEvent.Cancelled -> runtime.finishAssistant("متوقف شد.")
                    TaskEvent.Blocked -> runtime.finishAssistant("یک دیالوگ سیستم مانع شد.")
                    is TaskEvent.Status -> runtime.setWorkStatus(event.message)
                    is TaskEvent.Progress -> runtime.setWorkStatus(event.description)
                    is TaskEvent.ToolAction -> runtime.setWorkStatus(event.toolName)
                    else -> Unit
                }
            }
        }
    }
}
