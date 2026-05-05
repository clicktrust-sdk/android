package cc.clicktrust.sdk.signals

import android.content.Context
import android.provider.Settings
import cc.clicktrust.sdk.internal.LifecycleTracker
import cc.clicktrust.sdk.models.ViewabilityInfo

/**
 * Snapshots whether the app appears to be foreground / on-screen at
 * the moment of collect. Pairs with the click-injection trap
 * server-side: clicks that fire when [inForeground] is false are
 * almost always programmatically triggered.
 *
 * `screenBrightness` is the system-wide brightness slider value (not
 * the per-window override) — clicks that come in at brightness 0 from
 * a "real" user are very rare and weight the bot score upward.
 */
internal object ViewabilitySignals {

    fun snapshot(context: Context): ViewabilityInfo {
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()?.let { it.coerceIn(0, 255) / 255f } ?: -1f

        return ViewabilityInfo(
            inForeground = LifecycleTracker.isForeground(),
            sceneActive = LifecycleTracker.isResumed(),
            screenBrightness = brightness,
        )
    }
}
