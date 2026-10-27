package fr.toolmap.testing

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf

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

/**
 * Cancels this view model's coroutines and turns the main looper until they have all finished: for a view model whose
 * coroutines run on the main looper of Robolectric (a compose test), which [stopAndJoin] would hold while it waits.
 */
fun ViewModel.stopTurningLooper() {
    val job = viewModelScope.coroutineContext.job
    job.cancel()
    val end = System.currentTimeMillis() + STOP_TIMEOUT_MS
    while (!job.isCompleted) {
        check(System.currentTimeMillis() < end) { "The view model did not stop" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(POLL_MS)
    }
}

private const val POLL_MS = 10L
