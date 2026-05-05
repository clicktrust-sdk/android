package cc.clicktrust.sdk.models

import org.json.JSONObject

/**
 * Wire payload for `POST /api/collect`.
 *
 * Field names + nesting MUST match the iOS SDK and the JS snippet
 * byte-for-byte — the server's analyzer reads these by exact JSON key
 * and any rename here silently degrades a trap. Order doesn't matter
 * for the analyzer (it reads as a map) but DOES matter for HMAC
 * because the signature covers the raw POST body bytes — so we serialize
 * via [toJson] which pins the iteration order so re-encoding the same
 * payload twice produces the same bytes.
 *
 * Optional fields are encoded as JSON `null` keys; that mirrors the
 * iOS encoder's behavior for `Optional` properties (it omits absent
 * keys from `nil`, but the server is tolerant of either shape — we
 * include them for symmetry with the JS snippet which always emits
 * the key).
 *
 * Keep this file additive: new server-side traps may need new fields,
 * and pruning would silently break older deployments still hitting
 * `/api/collect`.
 */
internal data class CollectPayload(
    // ── Identity / app ─────────────────────────────────────────────
    val tid: String,
    val platform: String = "android",
    val bundleId: String,                 // BuildConfig.APPLICATION_ID
    val appVersion: String?,              // PackageInfo.versionName
    val osVersion: String,                // Build.VERSION.RELEASE
    val deviceModel: String,              // Build.MODEL
    val deviceIdHash: String,             // SHA-256 of Settings.Secure.ANDROID_ID

    // ── Snippet-aligned ────────────────────────────────────────────
    val ua: String,                       // Synthetic "ClickTrust-Android/<ver> (...)"
    val lang: String,
    val langs: List<String>,
    val timezone: String,
    val timezoneOffset: Int,              // minutes east of UTC, matches JS Date.getTimezoneOffset() inverted sign
    val screenW: Int,
    val screenH: Int,
    val colorDepth: Int = 24,
    val cookiesEnabled: Boolean = false,  // No cookies in native — kept for trap symmetry
    val touchPoints: Int = 5,
    val hardwareConcurrency: Int,
    val deviceMemory: Int,                // GiB rounded down
    val doNotTrack: Boolean,
    val webdriver: Boolean = false,
    val url: String?,                     // Deep-link / current screen route (best-effort)
    val referer: String?,                 // Install referrer when available
    val timestamp: Long,                  // ms since epoch

    // ── Nested telemetry ───────────────────────────────────────────
    val connection: ConnectionInfo,
    val behavioral: BehavioralInfo,
    val antiDetect: AntiDetectInfo,
    val viewability: ViewabilityInfo,

    // ── Captcha challenge response ─────────────────────────────────
    // Populated only on the next /api/collect after a captcha solve so
    // the server can clear a soft-block. Matches the protocol the iOS
    // SDK uses and the server's ChallengeVerifier expects.
    val challenge: ChallengeProof? = null,
) {
    /**
     * Serialize to canonical JSON. The HMAC signer feeds the resulting
     * UTF-8 bytes to [cc.clicktrust.sdk.transport.HmacSigner] — never
     * round-trip through [JSONObject.toString] elsewhere or the
     * signature will diverge from what the server recomputes.
     */
    internal fun toJson(): String {
        val root = JSONObject()
        // Field order matches iOS' Codable property declaration order
        // (mostly stable but not guaranteed). Choosing one explicit
        // order keeps the bytes deterministic across builds.
        root.put("tid", tid)
        root.put("platform", platform)
        root.put("bundleId", bundleId)
        root.put("appVersion", appVersion ?: JSONObject.NULL)
        root.put("osVersion", osVersion)
        root.put("deviceModel", deviceModel)
        root.put("deviceIdHash", deviceIdHash)
        root.put("ua", ua)
        root.put("lang", lang)
        root.put("langs", langs.toJsonArray())
        root.put("timezone", timezone)
        root.put("timezoneOffset", timezoneOffset)
        root.put("screenW", screenW)
        root.put("screenH", screenH)
        root.put("colorDepth", colorDepth)
        root.put("cookiesEnabled", cookiesEnabled)
        root.put("touchPoints", touchPoints)
        root.put("hardwareConcurrency", hardwareConcurrency)
        root.put("deviceMemory", deviceMemory)
        root.put("doNotTrack", doNotTrack)
        root.put("webdriver", webdriver)
        root.put("url", url ?: JSONObject.NULL)
        root.put("referer", referer ?: JSONObject.NULL)
        root.put("timestamp", timestamp)
        root.put("connection", connection.toJson())
        root.put("behavioral", behavioral.toJson())
        root.put("antiDetect", antiDetect.toJson())
        root.put("viewability", viewability.toJson())
        challenge?.let { root.put("challenge", it.toJson()) }
        return root.toString()
    }
}

internal data class ConnectionInfo(
    val effectiveType: String,            // wifi / cellular / none / unknown
    val carrier: String?,
    val isVpn: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("effectiveType", effectiveType)
        put("carrier", carrier ?: JSONObject.NULL)
        put("isVpn", isVpn)
    }
}

internal data class BehavioralInfo(
    val tapCount: Int,
    val scrollCount: Int,
    val totalDwellMs: Long,
    val orientationChanges: Int,
    val foregroundCount: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tapCount", tapCount)
        put("scrollCount", scrollCount)
        put("totalDwellMs", totalDwellMs)
        put("orientationChanges", orientationChanges)
        put("foregroundCount", foregroundCount)
    }
}

internal data class AntiDetectInfo(
    val rooted: Boolean,                  // su binary / Magisk / root file paths present
    val debuggerAttached: Boolean,        // Debug.isDebuggerConnected() / android.os.Debug.waitingForDebugger()
    val emulator: Boolean,                // Build.FINGERPRINT contains "generic" etc.
    val resignedBundle: Boolean,          // signing cert sha-256 ≠ configured allowlist (or default-trust mode → false)
    val xposedDetected: Boolean = false,  // Xposed / LSPosed framework hooks visible
    val frida: Boolean = false,           // common Frida server ports / libs
) {
    fun toJson(): JSONObject = JSONObject().apply {
        // Server-side trap reads `jailbroken` for cross-platform
        // symmetry with iOS — alias `rooted` so the same analyzer
        // handles both.
        put("jailbroken", rooted)
        put("rooted", rooted)
        put("debuggerAttached", debuggerAttached)
        put("emulator", emulator)
        put("resignedBundle", resignedBundle)
        put("xposedDetected", xposedDetected)
        put("frida", frida)
    }
}

internal data class ViewabilityInfo(
    val inForeground: Boolean,
    val sceneActive: Boolean,             // Equivalent to iOS scenePhase == active
    val screenBrightness: Float,          // 0.0..1.0; -1.0 if unknown
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("inForeground", inForeground)
        put("sceneActive", sceneActive)
        put("screenBrightness", screenBrightness.toDouble())
    }
}

internal data class ChallengeProof(
    val token: String,
    val nonce: Long,
    val tappedColor: String,
    val solveMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("token", token)
        put("nonce", nonce)
        put("tappedColor", tappedColor)
        put("solveMs", solveMs)
    }
}

// JSONArray helper — kept private to this file to avoid leaking a
// general-purpose extension into the SDK's public surface.
private fun List<String>.toJsonArray(): org.json.JSONArray {
    val arr = org.json.JSONArray()
    for (s in this) arr.put(s)
    return arr
}
