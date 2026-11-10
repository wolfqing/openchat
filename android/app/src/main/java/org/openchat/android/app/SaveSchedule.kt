package org.openchat.android.app

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * When to write the mesh state after a change: once changes stop for [quietMs], but never later
 * than [maxWaitMs] after the first one not yet written. A plain quiet-time debounce never fires
 * while changes keep coming, and in a crowd they do: the state would not reach the disk at all.
 */
class SaveSchedule(private val quietMs: Long = 500, private val maxWaitMs: Long = 5_000) {
    private var firstUnsaved = -1L

    /** A change happened at [now]; returns how long from now the write should run. */
    fun delayAfterChange(now: Long): Long {
        if (firstUnsaved < 0) firstUnsaved = now
        return minOf(quietMs, maxOf(0L, firstUnsaved + maxWaitMs - now))
    }

    /** The write ran (or its content was thrown away by a wipe). */
    fun saved() { firstUnsaved = -1 }
}

/**
 * Asks the mesh thread a question from another thread, usually the UI's. [post] queues work on
 * the mesh thread and says whether it took it. The caller gets [fallback] instead of waiting for
 * good when that thread has quit (the service is going away) or is busy past [timeoutMs]; a
 * late answer is dropped, though the work itself may still be done.
 */
fun <T> askMesh(post: (Runnable) -> Boolean, timeoutMs: Long, fallback: T, block: () -> T): T {
    val task = FutureTask(block)
    if (!post(task)) return fallback
    return try { task.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: Exception) { fallback }
}
