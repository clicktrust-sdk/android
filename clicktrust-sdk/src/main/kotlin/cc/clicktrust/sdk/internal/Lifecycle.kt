package cc.clicktrust.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import cc.clicktrust.sdk.signals.BehavioralSignals
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralised activity-lifecycle observer.
 *
 *  - Reports foreground / background transitions to [BehavioralSignals]
 *    so dwell + foreground-count are accurate.
 *  - Tracks the current foreground activity as a [WeakReference] so
 *    the captcha overlay knows what to attach to without forcing the
 *    activity to leak.
 *  - Surfaces orientation changes via [BehavioralSignals.recordOrientationChange]
 *    so the analyzer can spot bots that never rotate.
 *
 * Registration is done once from [cc.clicktrust.sdk.ClickTrust.configure]
 * via [register]; calling it twice is a no-op.
 */
internal object LifecycleTracker : Application.ActivityLifecycleCallbacks, ComponentCallbacksHook {

    private val started = AtomicInteger(0)
    private val resumed = AtomicInteger(0)
    private val currentActivityRef = AtomicReference<WeakReference<Activity>?>(null)

    @Volatile private var registered: Boolean = false
    @Volatile private var lastConfig: Configuration? = null
    @Volatile private var onForeground: (() -> Unit)? = null
    @Volatile private var onBackground: (() -> Unit)? = null

    /** Hook the SDK's foreground/background callbacks. Both nullable. */
    fun configureCallbacks(onForeground: (() -> Unit)?, onBackground: (() -> Unit)?) {
        this.onForeground = onForeground
        this.onBackground = onBackground
    }

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(this)
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                this@LifecycleTracker.onConfigurationChanged(newConfig)
            }
            override fun onLowMemory() { /* not interesting */ }
        })
    }

    fun currentActivity(): Activity? = currentActivityRef.get()?.get()
    fun isForeground(): Boolean = started.get() > 0
    fun isResumed(): Boolean = resumed.get() > 0

    // ── Application.ActivityLifecycleCallbacks ───────────────────
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef.get()?.get() === activity) {
            currentActivityRef.set(null)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val n = started.incrementAndGet()
        if (n == 1) {
            BehavioralSignals.recordForeground()
            try { onForeground?.invoke() } catch (t: Throwable) { Logger.w("onForeground hook threw", t) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val n = started.decrementAndGet().coerceAtLeast(0)
        if (n == 0) {
            BehavioralSignals.recordBackground()
            try { onBackground?.invoke() } catch (t: Throwable) { Logger.w("onBackground hook threw", t) }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed.incrementAndGet()
        currentActivityRef.set(WeakReference(activity))
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.decrementAndGet().coerceAtLeast(0)
    }

    // ── ComponentCallbacks (orientation) ─────────────────────────
    override fun onConfigurationChanged(newConfig: Configuration) {
        val prev = lastConfig
        if (prev != null && prev.orientation != newConfig.orientation) {
            BehavioralSignals.recordOrientationChange()
        }
        lastConfig = Configuration(newConfig)
    }
}

/**
 * Tiny interface so the lifecycle tracker can fan-in
 * `onConfigurationChanged` through the same listener it already uses
 * for activity callbacks. Kept internal — consumers never see it.
 */
internal interface ComponentCallbacksHook {
    fun onConfigurationChanged(newConfig: Configuration)
}
