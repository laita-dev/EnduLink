package com.oscar.endulink.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSThread
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
actual fun sharePlainText(subject: String, text: String) {
    val present: () -> Unit = {
        try {
            val root = UIApplication.sharedApplication.keyWindow?.rootViewController
            if (root != null) {
                var top: UIViewController = root
                while (top.presentedViewController != null) {
                    top = top.presentedViewController ?: break
                }
                val activityVC = UIActivityViewController(
                    activityItems = listOf(text),
                    applicationActivities = null
                )
                activityVC.popoverPresentationController?.sourceView = top.view
                top.presentViewController(activityVC, animated = true, completion = null)
            }
        } catch (_: Exception) {}
    }
    if (NSThread.isMainThread) {
        present()
    } else {
        dispatch_async(dispatch_get_main_queue(), present)
    }
}
