package cc.clicktrust.sdk

import android.net.Uri

/**
 * Startup configuration for the ClickTrust SDK.
 *
 * Construct one of these per app launch and hand it to
 * [ClickTrust.configure]. All fields are immutable so a misconfigured
 * value produces a clear failure at construction time rather than a
 * silent network drop later.
 *
 * Mirrors the iOS [ClickTrustConfiguration] struct field-for-field so
 * cross-platform docs share the same names.
 */
public data class ClickTrustConfig(
    /**
     * Tracking id created when you registered the native account. Maps
     * 1:1 to the JS snippet's `tid`. Min length 6 — enforced by
     * [validate].
     */
    public val trackingId: String,

    /**
     * API base URL — typically `https://app.clicktrust.cc`. http URLs
     * are accepted so on-prem / staging environments work, but a
     * warning is logged.
     */
    public val apiBase: String,

    /**
     * Per-account SDK secret returned by `POST /api/accounts` (or by
     * `POST /api/accounts/:id/rotate-sdk-secret`). 32+ chars. Used as
     * the HMAC-SHA256 key for every request.
     */
    public val sdkSecret: String,

    /** Verbose logcat output — keep `false` in release builds. */
    public val debugLogging: Boolean = false,

    /**
     * Max session events buffered before forcing a flush. Matches the
     * iOS default. Hitting this cap triggers an immediate flush
     * regardless of [flushIntervalMs].
     */
    public val maxEventsPerBatch: Int = 50,

    /**
     * Periodic flush interval for the session recorder. Default 3s
     * matches iOS — ~95% of sessions deliver every event in one or two
     * round-trips at this cadence.
     */
    public val flushIntervalMs: Long = 3_000L,

    /**
     * When true (default), a `challenge` verdict automatically presents
     * the captcha overlay. Disable if you want to render your own UI
     * with [ClickTrust.onVerdict].
     */
    public val presentCaptchaAutomatically: Boolean = true,
) {
    /**
     * Sanity-check the config and produce a normalised copy. Throws
     * [IllegalArgumentException] with a human-readable message on the
     * first invalid field — never returns silently with a broken
     * value.
     */
    internal fun validate(): ClickTrustConfig {
        val trimmedTid = trackingId.trim()
        require(trimmedTid.length >= 6) {
            "ClickTrustConfig.trackingId must be at least 6 characters (got ${trimmedTid.length})"
        }
        val parsed = runCatching { Uri.parse(apiBase) }.getOrNull()
            ?: throw IllegalArgumentException("ClickTrustConfig.apiBase is not a valid URL: $apiBase")
        val scheme = parsed.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            "ClickTrustConfig.apiBase must be http or https (got $scheme)"
        }
        require(parsed.host?.isNotBlank() == true) {
            "ClickTrustConfig.apiBase must include a host (got $apiBase)"
        }
        val trimmedSecret = sdkSecret.trim()
        require(trimmedSecret.length >= 32) {
            "ClickTrustConfig.sdkSecret must be at least 32 characters (got ${trimmedSecret.length})"
        }
        require(maxEventsPerBatch in 1..1000) {
            "ClickTrustConfig.maxEventsPerBatch must be 1..1000"
        }
        require(flushIntervalMs in 250..60_000) {
            "ClickTrustConfig.flushIntervalMs must be 250..60000ms"
        }
        // Strip trailing slash on apiBase so the transport doesn't
        // produce `https://app.clicktrust.cc//api/collect`.
        val normalisedBase = apiBase.trimEnd('/')
        return copy(
            trackingId = trimmedTid,
            apiBase = normalisedBase,
            sdkSecret = trimmedSecret,
        )
    }
}
