package cc.clicktrust.sdk.internal

import android.util.Log

/**
 * Thin logcat wrapper. All SDK logs use the tag "ClickTrust" so apps
 * can filter or silence the SDK with one `setprop` rule. Verbose
 * logging is gated on [debugEnabled] so a release-mode app doesn't
 * leak signal-collection diagnostics.
 *
 * Mirrors the iOS `Logger.swift` `ctLog` / `ctWarn` API.
 */
internal object Logger {
    @Volatile var debugEnabled: Boolean = false
    private const val TAG = "ClickTrust"

    fun d(msg: String) { if (debugEnabled) Log.d(TAG, msg) }
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String) { Log.w(TAG, msg) }
    fun w(msg: String, t: Throwable) { Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
    }
}
