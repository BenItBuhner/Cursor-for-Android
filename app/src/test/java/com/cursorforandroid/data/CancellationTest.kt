package com.cursorforandroid.data

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {

    @Test
    fun `a value and a failure come back as runCatching gives them`() = runTest {
        assertThat(runCatchingCancellable { 7 }.getOrNull()).isEqualTo(7)
        val failed = runCatchingCancellable<Int> { throw IOException("offline") }
        assertThat(failed.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a cancelled caller is cancelled, not handed a failure to act on`() = runTest {
        var actedOnFailure = false
        val job = launch {
            runCatchingCancellable { awaitCancellation() }
            actedOnFailure = true
        }
        runCurrent()
        job.cancel()
        job.join()
        assertThat(actedOnFailure).isFalse()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `the standard runCatching lets the cancelled caller carry on, which is what this replaces`() = runTest {
        var actedOnFailure = false
        val job = launch {
            runCatching { awaitCancellation() }
            actedOnFailure = true
        }
        runCurrent()
        job.cancel()
        job.join()
        assertThat(actedOnFailure).isTrue()
    }

    @Test
    fun `a timeout inside the block is the block's failure`() = runTest {
        val result = runCatchingCancellable { withTimeout(10) { delay(1_000) } }
        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
    }

    @Test
    fun `a cancellation thrown by the block of a live caller is the block's failure`() = runTest {
        val result = runCatchingCancellable<Unit> { throw CancellationException("the stream closed") }
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `cleanup that must not be cancelled still gets its result`() = runTest {
        var cleaned: Result<Unit>? = null
        val job = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleaned = runCatchingCancellable { throw CancellationException("gone") } }
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertThat(cleaned?.isFailure).isTrue()
    }
}
