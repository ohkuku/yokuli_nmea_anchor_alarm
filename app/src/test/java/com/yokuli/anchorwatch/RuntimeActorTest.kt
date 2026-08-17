package com.yokuli.anchorwatch

import com.yokuli.anchorwatch.runtime.SerialRuntimeActor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeActorTest {
    @Test fun mailboxWaitsForRestoreAndPreservesSubmissionOrder() = runBlocking {
        val restore = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val order = mutableListOf<Int>()
        val complete = CompletableDeferred<Unit>()
        val failures = mutableListOf<Throwable>()
        val actor = SerialRuntimeActor(scope, { restore.await() }, failures::add)

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
        val anchor = SerialRuntimeActor(scope, { restore.await() }) { throw it }
        val proxy = SerialRuntimeActor(scope, { restore.await() }) { throw it }

        anchor.submit { anchorStarted.complete(Unit);releaseAnchor.await() }
        withTimeout(2_000) { anchorStarted.await() }
        proxy.submit { proxyFinished.complete(Unit) }
        withTimeout(2_000) { proxyFinished.await() }
        assertFalse(releaseAnchor.isCompleted)

        releaseAnchor.complete(Unit)
        anchor.shutdown();proxy.shutdown();scope.cancel()
    }
}
