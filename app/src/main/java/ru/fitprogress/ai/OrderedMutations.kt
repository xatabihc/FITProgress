package ru.fitprogress.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Entered undispatched by the ViewModel, so UI actions queue before dispatching database work. */
class OrderedMutations {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
