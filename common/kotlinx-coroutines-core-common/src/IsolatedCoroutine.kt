/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.*

internal expect fun <T> isolateCoroutine(
    completion: Continuation<T>,
    builder: (completion: Continuation<T>) -> Continuation<Unit>
): Continuation<Unit>

internal expect fun isolateRunnable(task: Runnable): Runnable