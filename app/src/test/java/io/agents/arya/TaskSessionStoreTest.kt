package io.agents.arya

import io.agents.arya.store.MemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TaskSessionStoreTest {

    private fun store(kv: MemoryKeyValueStore = MemoryKeyValueStore()) =
        TaskSessionStore(kv = kv, debugTransitions = false)

    @Test
    fun initialStateIsIdle() {
        val s = store()
        assertTrue(s.state.value is TaskState.Idle)
        assertTrue(s.state.value.isTerminal())
    }

    @Test
    fun startTransitionsToRouting() {
        val s = store()
        s.start("task-1", "open telegram")
        assertTrue(s.state.value is TaskState.Routing)
        assertEquals("task-1", s.state.value.taskId)
        assertEquals("open telegram", (s.state.value as TaskState.Routing).prompt)
        assertFalse(s.state.value.isTerminal())
    }

    @Test
    fun tier1RoutingFinishesImmediately() {
        val s = store()
        s.start("task-2", "what time is it")
        s.transition(Transition.Routed(isTier1 = true))
        assertTrue(s.state.value is TaskState.Finished)
        assertTrue(s.state.value.isTerminal())
    }

    @Test
    fun executingStepsAndFinish() {
        val s = store()
        s.start("task-3", "send a message")
        s.transition(Transition.Routed(isTier1 = false))
        assertTrue(s.state.value is TaskState.Executing)
        s.transition(Transition.StepStarted(1, "find contact"))
        assertEquals("find contact", (s.state.value as TaskState.Executing).stepDescription)
        s.transition(Transition.Finish("message sent"))
        assertTrue(s.state.value is TaskState.Finished)
        assertEquals("message sent", (s.state.value as TaskState.Finished).resultSummary)
    }

    @Test
    fun confirmApproveAndDeny() {
        val s = store()
        s.start("c1", "send")
        s.transition(Transition.Routed(false))
        s.transition(Transition.RequireConfirm("send sms", "send_message", "{}"))
        assertTrue(s.state.value is TaskState.ConfirmPending)
        s.transition(Transition.ApproveConfirm)
        assertTrue(s.state.value is TaskState.Executing)

        val s2 = store()
        s2.start("c2", "send")
        s2.transition(Transition.Routed(false))
        s2.transition(Transition.RequireConfirm("send sms", "send_message", "{}"))
        s2.transition(Transition.DenyConfirm)
        assertTrue(s2.state.value is TaskState.Cancelled)
        assertTrue((s2.state.value as TaskState.Cancelled).byUser)
    }

    @Test
    fun requestStopFlipsToStoppingAndCancelled() {
        val s = store()
        s.start("task-4", "long task")
        s.transition(Transition.Routed(false))
        s.requestStop()
        assertTrue(s.state.value is TaskState.Stopping)
        s.transition(Transition.Stopped)
        assertTrue(s.state.value is TaskState.Cancelled)
    }

    @Test
    fun errorGoesToFailed() {
        val s = store()
        s.start("e1", "x")
        s.transition(Transition.Routed(false))
        s.transition(Transition.Error("boom"))
        assertTrue(s.state.value is TaskState.Failed)
        assertEquals("boom", (s.state.value as TaskState.Failed).reason)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotStartWhileRunning() {
        val s = store()
        s.start("a", "one")
        s.start("b", "two")
    }

    @Test
    fun restoreNonTerminalBecomesFailed() {
        val kv = MemoryKeyValueStore()
        val first = store(kv)
        first.start("dead", "running when killed")
        first.transition(Transition.Routed(false))
        assertFalse(first.state.value.isTerminal())

        val restored = store(kv)
        assertTrue(restored.state.value is TaskState.Failed)
        val failed = restored.state.value as TaskState.Failed
        assertEquals("dead", failed.taskId)
        assertTrue(failed.reason.contains("closed") || failed.reason.contains("متوقف"))
    }

    @Test
    fun parallelStopsEndInOneTerminal() {
        val s = store()
        s.start("p", "parallel")
        s.transition(Transition.Routed(false))
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(100)
        val errors = AtomicInteger(0)
        repeat(100) {
            pool.execute {
                try {
                    s.requestStop()
                    s.transition(Transition.Stopped)
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())
        assertTrue(s.state.value.isTerminal())
        assertTrue(s.state.value is TaskState.Cancelled || s.state.value is TaskState.Finished)
    }

    @Test
    fun illegalTransitionsDoNotCorrupt() {
        val s = store()
        s.transition(Transition.ApproveConfirm)
        assertTrue(s.state.value is TaskState.Idle)
        s.start("x", "y")
        s.transition(Transition.DenyConfirm)
        assertTrue(s.state.value is TaskState.Routing)
    }
}
