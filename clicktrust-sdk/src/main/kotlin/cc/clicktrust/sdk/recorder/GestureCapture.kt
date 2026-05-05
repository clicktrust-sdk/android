package cc.clicktrust.sdk.recorder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.Window
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.signals.BehavioralSignals
import java.lang.ref.WeakReference

/**
 * Non-consuming gesture observer.
 *
 * We intercept touch events at the Window callback level and emit
 * synthetic [SessionEvent] entries via [SessionRecorder] AND bump
 * [BehavioralSignals] counters. Importantly we ALWAYS forward the
 * event to the original callback — the SDK is observation-only and
 * MUST NOT swallow taps even on its own captcha overlay (the overlay
 * has its own UI hierarchy that handles its taps independently).
 *
 * Activities are wrapped lazily in `onActivityResumed` so apps that
 * register the SDK after the first activity is already on-screen
 * still get observed. Each Window's existing callback is preserved
 * via [Window.Callback] delegation so we don't break apps that
 * already replaced their own callback.
 */
internal class GestureCapture(
    private val recorder: SessionRecorder,
) : Application.ActivityLifecycleCallbacks {

    private val attached: MutableMap<Int, WeakReference<Activity>> = HashMap()

    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        // Activity already on-screen at startup time? Nothing in
        // ActivityLifecycleCallbacks fires for the existing one until
        // the next configuration change, so we can't observe pre-SDK
        // taps. That's fine — collect-on-cold-start picks up the
        // current state and the very next foreground call will wire
        // in this listener.
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { wrapWindow(activity) }
    override fun onActivityStarted(activity: Activity) { wrapWindow(activity) }
    override fun onActivityResumed(activity: Activity) { wrapWindow(activity) }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        attached.remove(System.identityHashCode(activity))
    }

    private fun wrapWindow(activity: Activity) {
        val key = System.identityHashCode(activity)
        if (attached.containsKey(key)) return
        try {
            val original = activity.window.callback
            activity.window.callback = Delegating(original, recorder, activity::class.java.simpleName)
            attached[key] = WeakReference(activity)
        } catch (t: Throwable) {
            Logger.w("GestureCapture wrapWindow failed for ${activity.javaClass.name}", t)
        }
    }

    /**
     * Window.Callback delegate that forwards every method but
     * sniffs MotionEvents for taps + scrolls. Must implement every
     * method on the interface — Android's stock Window.Callback
     * handles a *lot* of menu / panel / pointer events and any
     * default-implementation gap breaks IME / hardware keys.
     */
    private class Delegating(
        private val inner: Window.Callback,
        private val recorder: SessionRecorder,
        private val screenName: String,
    ) : Window.Callback by inner {

        private var lastTouchTime = 0L
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null) sniff(event)
            return inner.dispatchTouchEvent(event)
        }

        private fun sniff(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchTime = e.eventTime
                    lastTouchX = e.x
                    lastTouchY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    val dt = e.eventTime - lastTouchTime
                    val dx = e.x - lastTouchX
                    val dy = e.y - lastTouchY
                    val moved = (dx * dx + dy * dy) > (TAP_SLOP_PX * TAP_SLOP_PX)
                    if (!moved && dt < TAP_MAX_MS) {
                        BehavioralSignals.recordTap()
                        recorder.record(
                            type = "tap",
                            x = e.x.toInt(),
                            y = e.y.toInt(),
                            screen = screenName,
                        )
                    } else if (moved) {
                        BehavioralSignals.recordScroll()
                        recorder.record(
                            type = "scroll",
                            x = e.x.toInt(),
                            y = e.y.toInt(),
                            screen = screenName,
                            meta = mapOf("dx" to dx.toInt(), "dy" to dy.toInt(), "ms" to dt),
                        )
                    }
                }
            }
        }

        companion object {
            private const val TAP_SLOP_PX = 24f      // Matches Android's default ViewConfiguration slop
            private const val TAP_MAX_MS = 350L      // Tap-vs-long-press boundary
        }
    }
}
