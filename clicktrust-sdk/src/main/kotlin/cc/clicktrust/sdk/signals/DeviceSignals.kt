package cc.clicktrust.sdk.signals

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.DisplayMetrics
import cc.clicktrust.sdk.internal.DeviceId
import cc.clicktrust.sdk.models.CollectPayload
import java.util.Locale
import java.util.TimeZone

/**
 * Snapshots the slow-changing device facts that go into every collect:
 * OS version, hardware model, screen dimensions, locale, timezone,
 * RAM, CPU count, language list, app version, synthetic UA. Mirrors
 * iOS' `DeviceSignals.snapshot` so cross-platform analyzers can reuse
 * the same field names.
 *
 * Fields here are cheap enough to recompute every collect (no caching
 * needed), but several depend on a [Context] so we accept it once and
 * derive everything from there.
 */
internal object DeviceSignals {

    /**
     * Build a partial CollectPayload with all the facts this signal
     * collector owns. Fields it doesn't fill (connection, behavioral,
     * antiDetect, viewability, challenge) are populated by the
     * specialised collectors and merged in [cc.clicktrust.sdk.ClickTrust].
     */
    fun snapshot(context: Context, trackingId: String): PartialPayload {
        val pkg = context.packageName
        val pi: PackageInfo? = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }.getOrNull()
        val appVersion = pi?.versionName

        val locale = Locale.getDefault()
        val lang = locale.toLanguageTag()
        val langs = listOf(lang) + extraLanguageTags(locale)
        val tz = TimeZone.getDefault()
        val tzOffsetMinutes = -tz.getOffset(System.currentTimeMillis()) / 60_000
        // Sign matches JS Date.prototype.getTimezoneOffset() (positive
        // for west of UTC). Server already normalises both signs, but
        // keeping iOS' contract avoids surprising the analyzer.

        val dm: DisplayMetrics = context.resources.displayMetrics
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val ramBytes = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.let { am -> ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem }
            ?: 0L
        val deviceMemoryGiB = if (ramBytes > 0) (ramBytes / (1024L * 1024L * 1024L)).toInt() else 0

        return PartialPayload(
            tid = trackingId,
            bundleId = pkg,
            appVersion = appVersion,
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            deviceIdHash = DeviceId.hashed(context),
            ua = syntheticUserAgent(appVersion),
            lang = lang,
            langs = langs,
            timezone = tz.id,
            timezoneOffset = tzOffsetMinutes,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            hardwareConcurrency = cpuCount,
            deviceMemory = deviceMemoryGiB,
        )
    }

    /**
     * `ClickTrust-Android/<appVer> (<deviceModel>; Android <osVer>; <hw>)`.
     *
     * The `ClickTrust-Android/` prefix is what the server's bot filter
     * uses to recognise legitimate native traffic — keep it stable
     * across releases. Adding new components is fine; renaming the
     * prefix breaks identification.
     */
    private fun syntheticUserAgent(appVersion: String?): String {
        val app = appVersion?.takeIf { it.isNotBlank() } ?: "0.0.0"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }
        val osv = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()
        val hw = Build.HARDWARE.orEmpty().ifBlank { "unknown" }
        return "ClickTrust-Android/$app ($device; Android $osv; $hw)"
    }

    /**
     * On Android 7+ a user can preference multiple locales. We surface
     * the secondary entries so the analyzer can spot mismatches
     * between the requested locale and the IP's geo. Older devices
     * just return their primary.
     */
    private fun extraLanguageTags(primary: Locale): List<String> {
        val configLocales = android.content.res.Resources.getSystem().configuration.locales
        if (configLocales.isEmpty) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until configLocales.size()) {
            val l = configLocales.get(i) ?: continue
            val tag = l.toLanguageTag()
            if (tag != primary.toLanguageTag() && tag !in out) out += tag
        }
        return out
    }

    /**
     * The slice of [CollectPayload] this collector knows how to fill.
     * Kept as a separate type so callers can `.merge()` collectors
     * without each one having to know about the others' fields.
     */
    internal data class PartialPayload(
        val tid: String,
        val bundleId: String,
        val appVersion: String?,
        val osVersion: String,
        val deviceModel: String,
        val deviceIdHash: String,
        val ua: String,
        val lang: String,
        val langs: List<String>,
        val timezone: String,
        val timezoneOffset: Int,
        val screenW: Int,
        val screenH: Int,
        val hardwareConcurrency: Int,
        val deviceMemory: Int,
    )
}
