package com.blackmarlin.downloader.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

class ActivityVisibility : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger(0)
    val visible get() = resumed.get() > 0
    override fun onActivityResumed(activity: Activity) { resumed.incrementAndGet() }
    override fun onActivityPaused(activity: Activity) { resumed.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
