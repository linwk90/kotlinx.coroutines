/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental.internal

@SymbolName("Kotlin_Worker_ensureNeverFrozen")
internal actual external fun Any.ensureNeverFrozen()

@SymbolName("Kotlin_Worker_freezeInternal")
private external fun freezeInternal(it: Any?)

internal actual inline fun <T> T.freeze(): T {
    freezeInternal(this)
    return this
}