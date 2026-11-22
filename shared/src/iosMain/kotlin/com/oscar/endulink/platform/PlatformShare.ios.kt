package com.oscar.endulink.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
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
                activityVC.popoverPresentationController?.let { popover ->
                    popover.sourceView = top.view
                    val centerRect = top.view.bounds.useContents {
                        CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0)
                    }
                    popover.sourceRect = centerRect
                }
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
