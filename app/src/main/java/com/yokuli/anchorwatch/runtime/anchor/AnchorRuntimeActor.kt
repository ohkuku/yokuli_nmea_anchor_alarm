package com.yokuli.anchorwatch.runtime.anchor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The only writer to [AnchorWatchRuntime] after restoration. High-rate fixes,
 * health transitions and user commands share this ordered mailbox, but never
 * block unrelated proxy, sharing, notification or sonar work.
 */
class AnchorRuntimeActor(
    scope: CoroutineScope,
    private val awaitRestore: suspend () -> Unit,
    private val runtime: AnchorWatchRuntime,
    mailboxCapacity: Int = 512,
    private val onFailure: (Throwable) -> Unit,
) {
    private data class Work(
        val action: suspend AnchorWatchRuntime.() -> Unit,
        val completion: CompletableDeferred<Unit>?,
    )

    private val channel = Channel<Work>(mailboxCapacity.coerceAtLeast(1))

    init {
        scope.launch {
            for (work in channel) {
                try {
                    awaitRestore()
                    work.action(runtime)
                    work.completion?.complete(Unit)
                } catch (error: Throwable) {
                    work.completion?.completeExceptionally(error) ?: onFailure(error)
                }
            }
        }
    }

    fun submit(action: suspend AnchorWatchRuntime.() -> Unit): Boolean =
        channel.trySend(Work(action, null)).isSuccess

    suspend fun execute(action: suspend AnchorWatchRuntime.() -> Unit) {
        val completion = CompletableDeferred<Unit>()
        channel.send(Work(action, completion))
        completion.await()
    }

    fun shutdown() = channel.close()
}
