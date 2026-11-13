package com.oscar.endulink.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InAppToastEvent(
    val id: Long,
    val message: String
)

object PlatformToastCenter {
    private val lock = SynchronizedLock()
    private var nextId = 0L
    private val _toast = MutableStateFlow<InAppToastEvent?>(null)
    val toast: StateFlow<InAppToastEvent?> = _toast.asStateFlow()

    fun show(message: String) {
        val id = lock.withLock { ++nextId }
        _toast.value = InAppToastEvent(id = id, message = message)
    }

    fun clearIfMatches(id: Long) {
        if (_toast.value?.id == id) {
            _toast.value = null
        }
    }
}

expect fun showPlatformToast(message: String)
expect fun closeApp()

