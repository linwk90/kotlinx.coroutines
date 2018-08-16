/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental.intrinsics

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import kotlin.coroutines.experimental.*
import kotlin.coroutines.experimental.intrinsics.*

/**
 * Use this function to start coroutine in an atomic way, so that it cannot be cancelled
 * while waiting to be dispatched.
 */
public fun <T> (suspend () -> T).startCoroutineAtomic(completion: Continuation<T>) =
    isolateCoroutine(completion) { createCoroutineUnchecked(it) }.resume(Unit)

/**
 * Use this function to start coroutine in an atomic way, so that it cannot be cancelled
 * while waiting to be dispatched.
 */
public fun <R, T> (suspend (R) -> T).startCoroutineAtomic(receiver: R, completion: Continuation<T>) =
    isolateCoroutine(completion) { createCoroutineUnchecked(receiver, it) }.resume(Unit)
