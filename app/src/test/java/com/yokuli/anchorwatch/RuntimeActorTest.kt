package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.runtime.SerialRuntimeActor
import com.yokuli.anchorwatch.runtime.RuntimeOwner
import com.yokuli.anchorwatch.runtime.RuntimeOwnerRegistry
import com.yokuli.anchorwatch.runtime.RuntimeRequirement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeActorTest {
    @Test fun conditionOwnerReleaseCannotStopNmeaStillOwnedBySharing(){
        val registry=RuntimeOwnerRegistry()
        registry.set(RuntimeOwner.CONDITION_MONITOR,RuntimeRequirement(needsNmeaTransport=true,needsWakeLock=true,needsWifiLock=true))
        registry.set(RuntimeOwner.NMEA_SHARING,RuntimeRequirement(needsNmeaTransport=true,needsWakeLock=true))
        registry.set(RuntimeOwner.CONDITION_MONITOR,null)
        val remaining=registry.snapshot();assertTrue(remaining.needsNmeaTransport);assertTrue(remaining.needsWakeLock);assertFalse(remaining.needsWifiLock);assertEquals(setOf(RuntimeOwner.NMEA_SHARING),remaining.owners)
    }
    @Test fun mailboxWaitsForRestoreAndPreservesSubmissionOrder() = runBlocking {
        val restore = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val order = mutableListOf<Int>()
        val complete = CompletableDeferred<Unit>()
        val failures = mutableListOf<Throwable>()
        val actor = SerialRuntimeActor(scope, { restore.await() }, onFailure = failures::add)

        assertTrue(actor.submit { order += 1 })
        assertTrue(actor.submit { order += 2; complete.complete(Unit) })
        assertFalse(complete.isCompleted)
        restore.complete(Unit)
        withTimeout(2_000) { complete.await() }

        assertEquals(listOf(1, 2), order)
        assertTrue(failures.isEmpty())
        actor.shutdown();scope.cancel()
    }

    @Test fun blockedAnchorStyleMailboxDoesNotBlockIndependentProxyMailbox() = runBlocking {
        val restore = CompletableDeferred(Unit)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseAnchor = CompletableDeferred<Unit>()
        val anchorStarted = CompletableDeferred<Unit>()
        val proxyFinished = CompletableDeferred<Unit>()
        val anchor = SerialRuntimeActor(scope, { restore.await() }, onFailure = { throw it })
        val proxy = SerialRuntimeActor(scope, { restore.await() }, onFailure = { throw it })

        anchor.submit { anchorStarted.complete(Unit);releaseAnchor.await() }
        withTimeout(2_000) { anchorStarted.await() }
        proxy.submit { proxyFinished.complete(Unit) }
        withTimeout(2_000) { proxyFinished.await() }
        assertFalse(releaseAnchor.isCompleted)

        releaseAnchor.complete(Unit)
        anchor.shutdown();proxy.shutdown();scope.cancel()
    }

    @Test fun telemetryMailboxIsBoundedWhileExecuteWaitsForCapacity() = runBlocking {
        val restore = CompletableDeferred<Unit>()
        val restoreEntered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = SerialRuntimeActor(scope, {
            restoreEntered.complete(Unit)
            restore.await()
        }, mailboxCapacity = 2, onFailure = { throw it })
        assertTrue(actor.submit { })
        // Wait for the actor to consume the first item. `yield()` only hints to the
        // scheduler and was racy on slower GitHub runners.
        withTimeout(2_000) { restoreEntered.await() }
        assertTrue(actor.submit { })
        assertTrue(actor.submit { })
        assertFalse(actor.submit { })

        val commandFinished = CompletableDeferred<Unit>()
        val commandJob = launch { actor.execute { commandFinished.complete(Unit) } }
        yield()
        assertFalse(commandFinished.isCompleted)
        restore.complete(Unit)
        withTimeout(2_000) { commandFinished.await() }

        commandJob.join()
        actor.shutdown();scope.cancel()
    }
}
