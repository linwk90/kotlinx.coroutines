/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.*

internal actual inline fun <T> isolateCoroutine(
    completion: Continuation<T>,
    crossinline builder: (completion: Continuation<T>) -> Continuation<Unit>
): Continuation<Unit> =
    builder(completion)

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun isolateRunnable(task: Runnable): Runnable =
    task