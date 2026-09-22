package infoscry.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutationCoordinatorTest {

    @Test
    fun `an ordinary mutation runs its block and returns the result`() = runBlocking {
        val coordinator = MutationCoordinator()

        val result = coordinator.withMutation { 41 + 1 }

        assertEquals(42, result)
        assertNull(coordinator.maintenanceInProgress)
    }

    @Test
    fun `ordinary mutations share the gate and may overlap`() = runBlocking {
        val coordinator = MutationCoordinator()
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val bothInside = CompletableDeferred<Unit>()

        val stages = (1..2).map {
            async {
                coordinator.withMutation {
                    val now = inside.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, now) }
                    if (now == 2) bothInside.complete(Unit)
                    withTimeout(TIMEOUT_MILLIS) { bothInside.await() }
                    inside.decrementAndGet()
                }
            }
        }
        stages.awaitAll()

        assertEquals(2, peak.get(), "two ordinary mutation stages must be admitted together")
    }

    @Test
    fun `exclusive maintenance drains active stages before it starts`() = runBlocking {
        val coordinator = MutationCoordinator()
        val stageStarted = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()
        val maintenanceRan = CompletableDeferred<Unit>()

        val stage = launch {
            coordinator.withMutation {
                stageStarted.complete(Unit)
                releaseStage.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { stageStarted.await() }

        val maintenance = async {
            coordinator.withExclusiveMaintenance("delete-collection") {
                assertTrue(maintenanceRan.isActive, "maintenance must not start before the stage has drained")
                maintenanceRan.complete(Unit)
            }
        }

        // While the request is pending, the operation is visible as maintenance in progress.
        withTimeout(TIMEOUT_MILLIS) {
            while (coordinator.maintenanceInProgress == null) delay(POLL_MILLIS)
        }
        assertEquals("delete-collection", coordinator.maintenanceInProgress)
        assertTrue(!maintenanceRan.isCompleted, "maintenance must wait for the active stage")

        releaseStage.complete(Unit)
        stage.join()
        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }

        assertNull(coordinator.maintenanceInProgress, "maintenance must clear itself when it finishes")
    }

    @Test
    fun `a mutating command during maintenance is refused instead of waiting`() = runBlocking {
        val coordinator = MutationCoordinator()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()

        val maintenance = async {
            coordinator.withExclusiveMaintenance("reindex") {
                maintenanceStarted.complete(Unit)
                releaseMaintenance.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { maintenanceStarted.await() }

        val refusal = assertFailsWith<MaintenanceInProgressException> {
            coordinator.withMutation { "never runs" }
        }

        assertEquals("reindex", refusal.operation)
        assertTrue(refusal.message.orEmpty().contains("reindex"))

        releaseMaintenance.complete(Unit)
        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }

        assertEquals("admitted again", coordinator.withMutation { "admitted again" })
    }

    @Test
    fun `a competing exclusive maintenance request is refused and can retry after release`() = runBlocking {
        val coordinator = MutationCoordinator()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()

        val maintenance = async {
            coordinator.withExclusiveMaintenance("reindex") {
                maintenanceStarted.complete(Unit)
                releaseMaintenance.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { maintenanceStarted.await() }

        val refusal = withTimeout(TIMEOUT_MILLIS) {
            assertFailsWith<MaintenanceInProgressException> {
                coordinator.withExclusiveMaintenance("delete-collection") { "never runs" }
            }
        }
        assertEquals("reindex", refusal.operation)

        releaseMaintenance.complete(Unit)
        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }
        assertEquals("retried", coordinator.withExclusiveMaintenance("delete-collection") { "retried" })
    }

    @Test
    fun `a job stage waits for maintenance and then runs`() = runBlocking {
        val coordinator = MutationCoordinator()
        val order = mutableListOf<String>()
        val maintenanceStarted = CompletableDeferred<Unit>()

        val maintenance = async {
            coordinator.withExclusiveMaintenance("delete-collection") {
                maintenanceStarted.complete(Unit)
                delay(HOLD_MILLIS)
                order += "maintenance"
            }
        }
        withTimeout(TIMEOUT_MILLIS) { maintenanceStarted.await() }

        val waiter = async { coordinator.awaitMutation { order += "stage" } }

        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }
        withTimeout(TIMEOUT_MILLIS) { waiter.await() }

        assertEquals(listOf("maintenance", "stage"), order)
    }

    @Test
    fun `exclusive maintenance does not overlap and rejects competing owners`() = runBlocking {
        val coordinator = MutationCoordinator()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val winner = async {
            coordinator.withExclusiveMaintenance("operation-1") {
                maintenanceStarted.complete(Unit)
                releaseMaintenance.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { maintenanceStarted.await() }
        withTimeout(TIMEOUT_MILLIS) {
            assertFailsWith<MaintenanceInProgressException> {
                coordinator.withExclusiveMaintenance("operation-2") { "never runs" }
            }
        }
        releaseMaintenance.complete(Unit)
        withTimeout(TIMEOUT_MILLIS) { winner.await() }
    }

    @Test
    fun `a mutation nested inside maintenance is refused rather than deadlocking`() = runBlocking {
        val coordinator = MutationCoordinator()

        val failure = assertFailsWith<MaintenanceInProgressException> {
            coordinator.withExclusiveMaintenance("delete-collection") {
                // The maintenance owner must use ungated internal steps, not re-enter the gate.
                coordinator.withMutation { "never runs" }
            }
        }

        assertEquals("delete-collection", failure.operation)
        assertNull(coordinator.maintenanceInProgress, "a failed maintenance must still release the gate")
    }

    @Test
    fun `a failed maintenance releases the gate for the next mutation`() = runBlocking {
        val coordinator = MutationCoordinator()

        assertFailsWith<IllegalStateException> {
            coordinator.withExclusiveMaintenance("reindex") { error("maintenance failed") }
        }

        assertEquals("still usable", coordinator.withMutation { "still usable" })
    }

    @Test
    fun `a maintenance request is reported while it waits for the drain`() = runBlocking {
        val coordinator = MutationCoordinator()
        val stageStarted = CompletableDeferred<Unit>()
        val releaseStage = CompletableDeferred<Unit>()

        val stage = launch {
            coordinator.withMutation {
                stageStarted.complete(Unit)
                releaseStage.await()
            }
        }
        withTimeout(TIMEOUT_MILLIS) { stageStarted.await() }

        val maintenance = async { coordinator.withExclusiveMaintenance("delete-collection") { } }
        withTimeout(TIMEOUT_MILLIS) {
            while (coordinator.maintenanceInProgress == null) delay(POLL_MILLIS)
        }
        assertNotNull(coordinator.maintenanceInProgress)
        val pending = coordinator.maintenanceInProgress
        assertTrue(pending != null && pending.isNotEmpty(), "the pending operation must be reported")

        releaseStage.complete(Unit)
        stage.join()
        withTimeout(TIMEOUT_MILLIS) { maintenance.await() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val HOLD_MILLIS = 50L
        const val POLL_MILLIS = 5L
    }
}
