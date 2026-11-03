package com.oscar.endulink.platform

expect class SynchronizedLock() {
    fun lock()
    fun unlock()
}

inline fun <T> SynchronizedLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

expect fun platformSleep(ms: Long)

