package com.oscar.endulink.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive

@OptIn(ExperimentalForeignApi::class)
actual object BackgroundServiceController {

    private var isKeepAliveActive = false

    actual fun startKeepAlive() {
        if (isKeepAliveActive) return
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                category = AVAudioSessionCategoryPlayback,
                withOptions = AVAudioSessionCategoryOptionMixWithOthers,
                error = null
            )
            session.setActive(true, error = null)
            isKeepAliveActive = true
        } catch (_: Exception) {}
    }

    actual fun stopKeepAlive() {
        if (!isKeepAliveActive) return
        try {
            val session = AVAudioSession.sharedInstance()
            session.setActive(false, error = null)
            isKeepAliveActive = false
        } catch (_: Exception) {}
    }
}

