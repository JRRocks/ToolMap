package fr.toolmap.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Cancels this view model's coroutines and waits until they have all finished. Call it in `@After` before closing
 * what they read (a database): a Room query still running on `Dispatchers.IO` would fail on the closed database, and
 * kotlinx-coroutines-test would report that exception in whichever test of the same JVM starts next
 * (`UncaughtExceptionsBeforeTest`), making an unrelated test fail at random.
 */
fun ViewModel.stopAndJoin() =
    runBlocking {
        withTimeout(STOP_TIMEOUT_MS) { viewModelScope.coroutineContext.job.cancelAndJoin() }
    }

private const val STOP_TIMEOUT_MS = 10_000L
