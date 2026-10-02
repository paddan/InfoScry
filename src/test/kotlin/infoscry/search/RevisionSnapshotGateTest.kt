package infoscry.search

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The reader half of the publication handoff, without a store underneath it.
 *
 * A publication writes the new reading's rows inside [RevisionSnapshotGate.handoff], and the two things
 * that make that safe are about timing rather than data: an acquisition that interleaves with the block
 * has to wait for the snapshot it publishes, and a lease taken before it has to keep the scope it was
 * handed. Both are asserted here with real threads, because a lease is taken by a search thread and a
 * handoff runs on a publication thread.
 */
class RevisionSnapshotGateTest {

    @Test
    fun aReaderArrivingDuringAHandoffIsHandedTheSnapshotItPublishes() {
        val gate = RevisionSnapshotGate()
        val published = RevisionScope(setOf("the-candidate"))
        val insideBlock = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val publisher = daemonThread {
            runBlocking {
                gate.handoff {
                    insideBlock.countDown()
                    assertTrue(releaseBlock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    published
                }
                Unit
            }
        }
        assertTrue(
            insideBlock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the handoff never reached the block it writes the new reading in",
        )

        // A reader that arrives now interleaves with the block. It must not be handed the scope the
        // handoff is replacing, which is the one the rows being written are not in yet.
        val arrived = AtomicReference<RevisionScope>()
        val arrival = daemonThread { gate.acquire().use { arrived.set(it.scope) } }
        Thread.sleep(SETTLE_MILLIS)
        assertNull(
            arrived.get(),
            "a reader arriving while a handoff is inside its block acquired a snapshot rather than waiting for it",
        )
        assertEquals(
            RevisionScope.EVERYTHING,
            gate.current(),
            "the previous scope is still what the readers already inside hold",
        )

        releaseBlock.countDown()
        publisher.join(TIMEOUT_MILLIS)
        assertFalse(publisher.isAlive, "the handoff did not finish")
        arrival.join(TIMEOUT_MILLIS)
        assertFalse(arrival.isAlive, "the reader that interleaved with the handoff never acquired")
        assertEquals(
            published,
            arrived.get(),
            "the reader that arrived during the handoff must be handed the snapshot the handoff publishes",
        )
        assertEquals(published, gate.current())
    }

    @Test
    fun aLeaseTakenBeforeAHandoffKeepsTheScopeItWasHanded() {
        val gate = RevisionSnapshotGate()
        val lease = gate.acquire()
        val published = RevisionScope(setOf("the-candidate"))
        val insideBlock = CountDownLatch(1)
        val releaseBlock = CountDownLatch(1)
        val publisher = daemonThread {
            runBlocking {
                gate.handoff {
                    insideBlock.countDown()
                    assertTrue(releaseBlock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    published
                }
                Unit
            }
        }

        // The handoff blocks new acquisitions at once, but it cannot write anything until the reader that
        // holds the previous reading has finished: writing while it was inside would lend it both readings.
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(
            1L,
            insideBlock.count,
            "a handoff wrote its new reading while a reader was still inside the one it replaces",
        )
        assertEquals(RevisionScope.EVERYTHING, gate.current(), "the handoff published a snapshot before its block ran")
        assertEquals(RevisionScope.EVERYTHING, lease.scope, "a lease keeps the scope it was handed")

        lease.close()
        assertTrue(insideBlock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the handoff never proceeded after the reader left")
        releaseBlock.countDown()
        publisher.join(TIMEOUT_MILLIS)
        assertFalse(publisher.isAlive, "the handoff did not finish")
        assertEquals(published, gate.current(), "the handoff published the snapshot it computed")
        assertEquals(RevisionScope.EVERYTHING, lease.scope, "the scope a lease was handed does not change under it")
    }

    /**
     * A seal refuses new acquisitions in the caller's own words, and unsealing serves them again.
     *
     * The reason is the only thing a reader can act on, so it has to travel with the refusal rather than
     * being replaced by a generic sentence somewhere above it.
     */
    @Test
    fun aSealedGateRefusesAcquisitionsWithItsReasonUntilItIsUnsealed() {
        val gate = RevisionSnapshotGate()
        gate.acquire().close()

        gate.seal("an authoritative publication could not be finished; restart to complete it")
        val refusal = assertFailsWith<RevisionSnapshotUnavailableException> { gate.acquire() }
        assertTrue(
            refusal.message.orEmpty().contains("restart to complete it"),
            "the refusal must carry the reason the seal was raised for, was ${refusal.message}",
        )
        // Sealing again does not rewrite the reason: the first one is the state the archive is in.
        gate.seal("a later, different reason")
        assertTrue(assertFailsWith<RevisionSnapshotUnavailableException> { gate.acquire() }.message.orEmpty().contains("restart"))

        gate.unseal()
        gate.acquire().close()
        assertEquals(
            RevisionScope.EVERYTHING,
            gate.current(),
            "unsealing refuses nothing and changes nothing else about the snapshot",
        )
    }

    private fun daemonThread(block: () -> Unit): Thread =
        Thread(block).apply { isDaemon = true; start() }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 30L
        const val TIMEOUT_MILLIS: Long = 30_000L

        /** How long a test waits before asserting that something did *not* happen. */
        const val SETTLE_MILLIS: Long = 250L
    }
}
