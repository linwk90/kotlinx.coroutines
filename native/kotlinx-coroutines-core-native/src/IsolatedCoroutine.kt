/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import konan.ThreadLocal
import konan.worker.*
import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlin.coroutines.experimental.*

internal actual inline fun <T> isolateCoroutine(
    completion: Continuation<T>,
    crossinline builder: (completion: Continuation<T>) -> Continuation<Unit>
): Continuation<Unit> {
    val context = completion.context
    val isoCompletion = IsoCompletion<T>(context, StableRef.create(completion).asCPointer()).freeze()
    val detached = !sameThreadContext(context)
    val continuationPtr = if (detached) {
        // this coroutine potentially dispatches to another thread -- detach it
        detachObjectGraph(TransferMode.CHECKED) {
            builder(isoCompletion)
        }!!
    } else {
        // this coroutine is confined to the current thread and we don't need to detach it
        StableRef.create(builder(isoCompletion)).asCPointer()
    }
    return IsoCoroutine<Unit>(context, continuationPtr, detached).freeze()
}

internal actual fun isolateRunnable(task: Runnable): Runnable {
    check(Current.continuation === task)
    return Current.isoCoroutine as Runnable
}

private fun sameThreadContext(context: CoroutineContext): Boolean {
    val interceptor = context[ContinuationInterceptor] ?: return true // no interceptor -> same thread
    val blockingEventLoop = interceptor as? BlockingEventLoop
    return blockingEventLoop != null && blockingEventLoop.threadId == LocalDispatcher.threadId
}

internal class IsoCompletion<in T>(
    override val context: CoroutineContext,
    val completionPtr: COpaquePointer
): Continuation<T> {
    override fun resume(value: T) {
        withRealCompletion { resume(value) }
    }

    override fun resumeWithException(exception: Throwable) {
        withRealCompletion { resumeWithException(exception) }
    }
}

private inline fun <T> IsoCompletion<T>.withRealCompletion(block: Continuation<T>.() -> Unit) {
    val stable = completionPtr.asStableRef<Continuation<T>>()
    val completion = stable.get()
    stable.dispose()
    // todo: dispose current DC
    block(completion)
}

internal class IsoCoroutine<in T>(
    override val context: CoroutineContext,
    continuationPtr: COpaquePointer,
    val detached: Boolean
) : RunnableContinuation<T> {
    val _continuationPtr = atomic(continuationPtr.toLong())
    
    override fun resume(value: T) {
        withRealContinuation { resume(value) }
    }

    override fun resumeWithException(exception: Throwable) {
        withRealContinuation { resumeWithException(exception) }
    }

    override fun resumeCancellable(value: T) {
        // Note: use `this.resume` to invoke the corresponding extension on Continuation
        withRealContinuation { this.resumeCancellable(value) }
    }

    override fun resumeCancellableWithException(exception: Throwable) {
        // Note: use `this.resume` to invoke the corresponding extension on Continuation
        withRealContinuation { this.resumeCancellableWithException(exception) }
    }

    override fun run() {
        withRealContinuation { (this as Runnable).run() }
    }

    fun getContinuationPtr(): COpaquePointer {
        val value = _continuationPtr.value
        check(value != -1L) { "Already disposed" }
        return value.toCPointer()!!
    }

    fun dispose() {
        val ptr = getContinuationPtr()
        if (detached) {
            attachObjectGraph<Any>(ptr)
        } else {
            ptr.asStableRef<Any>().dispose()
        }
        _continuationPtr.value = -1L
    }
}

private inline fun <T> IsoCoroutine<T>.withRealContinuation(crossinline block: Continuation<T>.() -> Unit) {
    val ptr = getContinuationPtr()
    if (detached) {
        val obj = detachObjectGraph {
            val continuation = attachObjectGraph<Continuation<T>>(ptr)
            val oldContinuation = Current.updateContinuation(continuation)
            val oldCoroutine = Current.updateIsoCoroutine(this)
            try {
                block(continuation)
            } finally {
                Current.continuation = oldContinuation
                Current.isoCoroutine = oldCoroutine
            }
            continuation
        }
        _continuationPtr.value = obj.toLong()
    } else {
        val continuation = ptr.asStableRef<Continuation<T>>().get()
        val oldContinuation = Current.updateContinuation(continuation)
        val oldCoroutine = Current.updateIsoCoroutine(this)
        try {
            block(continuation)
        } finally {
            Current.continuation = oldContinuation
            Current.isoCoroutine = oldCoroutine
        }
    }
}

@ThreadLocal
private object Current {
    internal var isoCoroutine: IsoCoroutine<*>? = null
    internal var continuation: Continuation<*>? = null

    internal fun updateIsoCoroutine(newCoroutine: IsoCoroutine<*>): IsoCoroutine<*>? =
        isoCoroutine.also { isoCoroutine = newCoroutine }

    internal fun updateContinuation(newContinuation: Continuation<*>): Continuation<*>? =
        continuation.also { continuation = newContinuation }
}