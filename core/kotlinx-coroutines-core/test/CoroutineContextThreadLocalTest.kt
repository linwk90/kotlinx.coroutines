/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import org.junit.Test
import kotlin.coroutines.experimental.*
import kotlin.test.*

class CoroutineContextThreadLocalTest : TestBase() {
    @Test
    fun testExample() = runTest {
        val exceptionHandler = coroutineContext[CoroutineExceptionHandler]!!
        val mainDispatcher = coroutineContext[ContinuationInterceptor]!!
        val mainThread = Thread.currentThread()
        val data = MyData()
        val element = MyElement(data)
        assertNull(myThreadLocal.get())
        val job = launch(element + exceptionHandler) {
            assertTrue(mainThread != Thread.currentThread())
            assertSame(element, coroutineContext[MyElement])
            assertSame(data, myThreadLocal.get())
            withContext(mainDispatcher) {
                assertSame(mainThread, Thread.currentThread())
                assertSame(element, coroutineContext[MyElement])
                assertSame(data, myThreadLocal.get())
            }
            assertTrue(mainThread != Thread.currentThread())
            assertSame(element, coroutineContext[MyElement])
            assertSame(data, myThreadLocal.get())
        }
        assertNull(myThreadLocal.get())
        job.join()
        assertNull(myThreadLocal.get())
    }

    @Test
    fun testUndispatched()= runTest {
        val exceptionHandler = coroutineContext[CoroutineExceptionHandler]!!
        val data = MyData()
        val element = MyElement(data)
        val job = launch(
            context = DefaultDispatcher + exceptionHandler + element,
            start = CoroutineStart.UNDISPATCHED
        ) {
            assertSame(data, myThreadLocal.get())
            yield()
            assertSame(data, myThreadLocal.get())
        }
        assertNull(myThreadLocal.get())
        job.join()
        assertNull(myThreadLocal.get())
    }
}

class MyData

// declare custom coroutine context element, storing some custom data of type MyData
class MyElement(val data: MyData) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MyElement>
}

// declare thread local variable holding MyData
private val myThreadLocal = ThreadLocal<MyData?>()

// declare extension point implementation
class MyCoroutineContextThreadLocal : CoroutineContextThreadLocal<MyElement, MyData?> {
    // provide the key of the corresponding context element
    override val key: CoroutineContext.Key<MyElement>
        get() = MyElement

    // this is invoked before coroutine is resumed on current thread
    override fun updateThreadContext(context: CoroutineContext, element: MyElement): MyData? {
        val oldState = myThreadLocal.get()
        myThreadLocal.set(element.data)
        return oldState
    }

    // this is invoked after coroutine has suspended on current thread
    override fun restoreThreadContext(context: CoroutineContext, oldState: MyData?) {
        myThreadLocal.set(oldState)
    }
}
