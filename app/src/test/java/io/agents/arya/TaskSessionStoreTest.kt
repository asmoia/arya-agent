package io.agents.arya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSessionStoreTest {

    @Test
    fun testInitialStateIsIdle() {
        val store = TaskSessionStore()
        assertTrue(store.state.value is TaskState.Idle)
        assertTrue(store.state.value.isTerminal())
    }

    @Test
    fun testStartTransitionsToRouting() {
        val store = TaskSessionStore()
        store.start("task-1", "تلگرام را باز کن")
        assertTrue(store.state.value is TaskState.Routing)
        assertEquals("task-1", store.state.value.taskId)
        assertEquals("تلگرام را باز کن", (store.state.value as TaskState.Routing).prompt)
        assertFalse(store.state.value.isTerminal())
    }

    @Test
    fun testTier1RoutingFinishesImmediately() {
        val store = TaskSessionStore()
        store.start("task-2", "ساعت چنده")
        store.transition(Transition.Routed(isTier1 = true))
        assertTrue(store.state.value is TaskState.Finished)
        assertTrue(store.state.value.isTerminal())
    }

    @Test
    fun testExecutingStepsAndFinish() {
        val store = TaskSessionStore()
        store.start("task-3", "پیام بفرست")
        store.transition(Transition.Routed(isTier1 = false))
        assertTrue(store.state.value is TaskState.Executing)

        store.transition(Transition.StepStarted(1, "جستجوی مخاطب"))
        assertEquals("جستجوی مخاطب", (store.state.value as TaskState.Executing).stepDescription)

        store.transition(Transition.Finish("پیام ارسال شد"))
        assertTrue(store.state.value is TaskState.Finished)
        assertEquals("پیام ارسال شد", (store.state.value as TaskState.Finished).resultSummary)
    }

    @Test
    fun testRequestStopFlipsToStoppingAndCancelled() {
        val store = TaskSessionStore()
        store.start("task-4", "پیمایش پیچیده")
        store.transition(Transition.Routed(isTier1 = false))

        store.requestStop()
        assertTrue(store.state.value is TaskState.Stopping)

        store.transition(Transition.Stopped)
        assertTrue(store.state.value is TaskState.Cancelled)
        assertTrue((store.state.value as TaskState.Cancelled).byUser)
    }
}
