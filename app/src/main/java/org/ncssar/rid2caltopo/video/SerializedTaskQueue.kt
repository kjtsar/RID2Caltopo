package org.ncssar.rid2caltopo.video

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SerializedTaskQueue(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
) {
    private var tail: Job? = null

    fun submit(task: suspend () -> Unit): Job {
        val previous = tail
        return scope.launch(context) {
            previous?.join()
            task()
        }.also { tail = it }
    }
}
