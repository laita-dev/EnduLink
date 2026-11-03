package com.oscar.endulink.platform

import platform.Foundation.NSLock
import platform.posix.usleep

actual class SynchronizedLock actual constructor() {
    private val delegate = NSLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

actual fun platformSleep(ms: Long) {
    usleep((ms * 1000).toUInt())
}

