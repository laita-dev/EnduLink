package com.oscar.endulink.platform

import java.util.concurrent.locks.ReentrantLock

actual class SynchronizedLock actual constructor() {
    private val delegate = ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

actual fun platformSleep(ms: Long) {
    Thread.sleep(ms)
}

