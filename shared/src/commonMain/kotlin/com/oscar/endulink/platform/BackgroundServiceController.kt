package com.oscar.endulink.platform

expect object BackgroundServiceController {
    fun startKeepAlive()
    fun stopKeepAlive()
}

