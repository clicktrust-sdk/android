package cc.clicktrust.sdk.signals

import android.content.Context
import android.provider.Settings

/**
 * Snapshots privacy preferences that the analyzer treats as soft
 * trust signals.
 *
 * Notably this does NOT touch the AdvertisingId Client (`com.google.
 * android.gms.ads.identifier`) — that pulls in Google Play services
 * and is gated by the new Play `AD_ID` permission. We deliberately
 * leave that integration to the consumer; if they want richer
 * attribution they can pass the AAID into [cc.clicktrust.sdk.ClickTrust]
 * via a future `setExternalAttribution(...)` call. For the core
 * collect we simply expose `doNotTrack` so the analyzer can downweight
 * fingerprint-based clustering for users who opted out.
 */
internal object PrivacySignals {

    fun doNotTrack(context: Context): Boolean {
        // `limit_ad_tracking` is the legacy global flag set by the
        // OEM's privacy menu (Android 10+ exposes it under Settings →
        // Google → Ads). Either presence of the flag set to 1 OR
        // the newer `ad_personalization_enabled = 0` counts as "do
        // not track".
        val resolver = context.contentResolver
        val limit = runCatching {
            Settings.Secure.getInt(resolver, "limit_ad_tracking")
        }.getOrDefault(0)
        if (limit == 1) return true
        val personalization = runCatching {
            Settings.Secure.getInt(resolver, "ad_personalization_enabled")
        }.getOrDefault(1)
        return personalization == 0
    }
}
