package com.yokuli.anchorwatch.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Ordered, non-blocking mailbox for a single runtime such as the GPS proxy. */
class SerialRuntimeActor(
    scope: CoroutineScope,
    private val awaitRestore: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private data class Work(val action: suspend () -> Unit, val completion: CompletableDeferred<Unit>?)
    private val channel = Channel<Work>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (work in channel) {
                try {
                    awaitRestore()
                    work.action()
                    work.completion?.complete(Unit)
                } catch (error: Throwable) {
                    work.completion?.completeExceptionally(error) ?: onFailure(error)
                }
            }
        }
    }

    fun submit(action: suspend () -> Unit): Boolean = channel.trySend(Work(action, null)).isSuccess

    suspend fun execute(action: suspend () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        if (!channel.trySend(Work(action, completion)).isSuccess) {
            throw IllegalStateException("Runtime actor is closed")
        }
        completion.await()
    }

    fun shutdown() = channel.close()
}
