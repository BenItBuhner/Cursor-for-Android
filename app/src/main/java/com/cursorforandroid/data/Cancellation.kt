package com.cursorforandroid.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * `runCatching` for a block that suspends. The standard one catches everything, a cancellation included: the coroutine
 * that was told to stop gets a failed [Result] instead, takes it for the request's own failure ("the account has no
 * such record", "the list is empty") and carries on — writing that reading into state — until it next suspends.
 *
 * This one gives the cancellation back to the coroutine it was meant for. A [CancellationException] is rethrown when
 * the caller itself has been cancelled, and only then: one that belongs to something inside the block — a
 * `withTimeout` that ran out, a child that was cancelled, a channel that closed — is a failure of the block like any
 * other and comes back as a [Result], as it always did.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(e)
    }
