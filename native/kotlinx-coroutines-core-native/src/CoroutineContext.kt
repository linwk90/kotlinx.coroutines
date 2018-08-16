/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.*
import kotlinx.coroutines.experimental.timeunit.*

private val lastThreadId = konan.worker.AtomicInt(0)

@ThreadLocal
internal object LocalDispatcher : CoroutineDispatcher(), Delay {
    internal val threadId: Int = lastThreadId.increment()
    
    private var currentEventLoop: BlockingEventLoop? = null

    internal fun updateCurrentEventLoop(newEventLoop: BlockingEventLoop): BlockingEventLoop? =
        currentEventLoop.also { currentEventLoop = newEventLoop }

    internal fun restoreCurrentEventLoop(oldEventLoop: BlockingEventLoop?) {
        currentEventLoop = oldEventLoop
    }

    private fun takeEventLoop(): BlockingEventLoop =
        currentEventLoop ?: error("There is no event loop. Use runBlocking { ... } to start one.")

    override fun dispatch(context: CoroutineContext, block: Runnable) =
        takeEventLoop().dispatch(context, block)
    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) =
        takeEventLoop().scheduleResumeAfterDelay(time, unit, continuation)
    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle =
        takeEventLoop().invokeOnTimeout(time, unit, block)

    fun execute(task: Runnable) {
        error("Cannot execute task because event loop was shut down")
    }

    fun schedule(delayedTask: EventLoopBase.DelayedTask) {
        error("Cannot schedule task because event loop was shut down")
    }

    fun removeDelayedImpl(delayedTask: EventLoopBase.DelayedTask) {
        error("Cannot happen")
    }
}

/**
 * This is the default [CoroutineDispatcher] that is used by all standard builders like
 * [launch], [async], etc if no dispatcher nor any other [ContinuationInterceptor] is specified in their context.
 */
public actual val DefaultDispatcher: CoroutineDispatcher = LocalDispatcher

internal actual val DefaultDelay: Delay = LocalDispatcher

/**
 * Creates context for the new coroutine. It installs [DefaultDispatcher] when no other dispatcher nor
 * [ContinuationInterceptor] is specified, and adds optional support for debugging facilities (when turned on).
 */
@Suppress("ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS")
public actual fun newCoroutineContext(context: CoroutineContext, parent: Job? = null): CoroutineContext {
    val wp = if (parent == null) context else context + parent
    return if (context !== DefaultDispatcher && context[ContinuationInterceptor] == null)
        wp + DefaultDispatcher else wp
}

// No debugging facilities on native
internal actual inline fun <T> withCoroutineContext(context: CoroutineContext, block: () -> T): T = block()
internal actual fun Continuation<*>.toDebugString(): String = toString()
internal actual val CoroutineContext.coroutineName: String? get() = null // not supported on native
