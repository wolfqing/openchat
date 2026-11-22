package org.openchat.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Enforces screen capture protection across every activity window in the process
 * (MainActivity, QR scanner CaptureActivity, and all Compose dialogs/sheets/menus
 * inheriting SecureFlagPolicy.Inherit), blocking screenshots, screen recording,
 * and system recents previews without exposing a bypass toggle.
 */
class OpenChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyScreenCaptureProtection(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

internal fun applyScreenCaptureProtection(activity: Activity) {
    activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.setRecentsScreenshotEnabled(false)
    }
}
