/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.*
import kotlin.coroutines.experimental.*

/**
 * An extension point to define elements in [CoroutineContext] that are installed into thread local
 * variables every time the coroutine with the specified element of type [E] in its context is resumed on a thread.
 *
 * Implementations of this interface also define a type [S] of the thread-local state that it needs to store on
 * resume of a coroutine and restore later on suspend and the infrastructure provides the corresponding storage.
 *
 * Implementations on this interface are looked up via [java.util.ServiceLoader].
 *
 * Example usage looks like this:
 *
 * ```
 * // declare custom coroutine context element, storing some custom data of type MyData
 * class MyElement(val data: MyData) : AbstractCoroutineContextElement(Key) {
 *     companion object Key : CoroutineContext.Key<MyElement>
 * }
 *
 * // declare thread local variable holding MyData
 * private val myThreadLocal = ThreadLocal<MyData?>()
 *
 * // declare extension point implementation
 * class MyCoroutineContextThreadLocal : CoroutineContextThreadLocal<MyElement, MyData?> {
 *     // provide the key of the corresponding context element
 *     override val key: CoroutineContext.Key<MyElement>
 *         get() = MyElement
 *
 *     // this is invoked before coroutine is resumed on current thread
 *     override fun updateThreadContext(context: CoroutineContext, element: MyElement): MyData? {
 *         val oldState = myThreadLocal.get()
 *         myThreadLocal.set(element.data)
 *         return oldState
 *     }
 *
 *     // this is invoked after coroutine has suspended on current thread
 *     override fun restoreThreadContext(context: CoroutineContext, oldState: MyData?) {
 *         myThreadLocal.set(oldState)
 *     }
 * }
 * ```
 *
 * Now, `MyCoroutineContextThreadLocal` fully qualified class named shall be registered via
 * `META-INF/services/kotlinx.coroutines.experimental.CoroutineContextThreadLocal` file.
 */
public interface CoroutineContextThreadLocal<E : CoroutineContext.Element, S> {
    /**
     * Key of the corresponding [CoroutineContext] element.
     */
    public val key: CoroutineContext.Key<E>

    /**
     * Updates context of the current thread.
     * This function is invoked before the coroutine in the specified [context] is resumed in the current thread
     * when the context of the coroutine contains the corresponding [element].
     * The result of this function is the old value of the thread-local state that will be passed to [restoreThreadContext].
     *
     * @param context the coroutine context.
     * @param element the context element with the corresponding [key].
     */
    public fun updateThreadContext(context: CoroutineContext, element: E): S

    /**
     * Restores context of the current thread.
     * This function is invoked after the coroutine in the specified [context] is suspended in the current thread
     * if [updateThreadContext] was previously invoked on resume of this coroutine.
     * The value of [oldState] is the result of the previous invocation of [updateThreadContext] and it should
     * be restored in the thread-local state by this function.
     *
     * @param context the coroutine context.
     * @param oldState the value returned by the previous invocation of [updateThreadContext].
     */
    public fun restoreThreadContext(context: CoroutineContext, oldState: S)
}

private typealias TL = CoroutineContextThreadLocal<CoroutineContext.Element, Any?>

/**
 * This class is used when one of many implementations of [CoroutineContextThreadLocal] are installed.
 * It is optimized for cases of zero or one _active_ implementations.
 */
internal class CoroutineContextThreadLocals(services: List<CoroutineContextThreadLocal<*, *>>) {
    @Suppress("UNCHECKED_CAST")
    private val servicesByKey = services.groupBy { it.key } as Map<CoroutineContext.Key<*>, List<TL>>
    private val zero = Symbol("ZERO")

    // Used when there are >= 2 active elements in the context
    private class Store(val context: CoroutineContext, n: Int) {
        val a = arrayOfNulls<Any>(n)
        var i = 0
    }

    // Counts active elements in the context
    // Any? here is Int | CoroutineContext.Element (when count is one)
    private val countActive =
        fun (countOrElement: Any?, element: CoroutineContext.Element): Any? {
            val inCount = countOrElement as? Int ?: 1
            val outCount = inCount + (servicesByKey[element.key]?.size ?: 0)
            return if (inCount == 0 && outCount == 1) element else outCount
        }

    // Find one (first) active thread local in the context, it is used when we know there is exactly one
    private val findOneActive =
        fun (found: TL?, element: CoroutineContext.Element): TL? {
            if (found != null) return found
            return servicesByKey[element.key]?.firstOrNull()
        }

    // Updates state for all active elements in the context using the given Store
    private val updateWithStore =
        fun (store: Store, element: CoroutineContext.Element): Store {
            servicesByKey[element.key]?.forEach { service ->
                store.a[store.i++] = service.updateThreadContext(store.context, element)
            }
            return store
        }

    // Restores state for all active elements in the context from the given Store
    private val restoreFromStore =
        fun (store: Store, element: CoroutineContext.Element): Store {
            servicesByKey[element.key]?.forEach { service ->
                service.restoreThreadContext(store.context, store.a[store.i++])
            }
            return store
        }

    fun updateThreadContext(context: CoroutineContext): Any? {
        val count = context.fold(0, countActive)
        @Suppress("IMPLICIT_BOXING_IN_IDENTITY_EQUALS")
        return when {
            count === 0 -> zero // very fast path when there are no active TL elements
            //    ^^^ identity comparison for speed, we know zero always has the same identity
            count is Int -> {
                // slow path for multiple active TL elements, allocates Store for multiple old values
                context.fold(Store(context, count), updateWithStore)
            }
            else -> {
                // fast path for one active TL element (no allocations, no additional context scan)
                val element = count as CoroutineContext.Element
                servicesByKey[element.key]!!.single().updateThreadContext(context, element)
            }
        }
    }

    fun restoreThreadContext(context: CoroutineContext, oldState: Any?) {
        when {
            oldState === zero -> return // very fast path when there are no active TL elements
            oldState is Store -> {
                // slow path with multiple stored active TL elements
                oldState.i = 0
                context.fold(oldState, restoreFromStore)
            }
            else -> {
                // fast path for one active TL element, but need to find it
                val service = context.fold(null, findOneActive)
                service!!.restoreThreadContext(context, oldState)
            }
        }
    }
}