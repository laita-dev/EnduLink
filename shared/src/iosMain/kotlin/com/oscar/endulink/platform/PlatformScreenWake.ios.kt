package com.oscar.endulink.platform

import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun setPlatformKeepScreenOn(enabled: Boolean) {
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
    }
}
