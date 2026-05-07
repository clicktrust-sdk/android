package cc.clicktrust.sdk.transport

import cc.clicktrust.sdk.ClickTrustConfig
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.SessionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Batches session-replay events into a single POST.
 *
 * Wire shape (matches iOS for `bundleId` byte-for-byte; the extra
 * `packageName` is the platform-natural alias so the server's
 * Android identity check works without depending on the legacy
 * iOS-derived key name):
 *
 *     {
 *       "sessionId": "<uuid>",
 *       "tid": "<tracking id>",
 *       "bundleId": "<bundle / package>",
 *       "packageName": "<bundle / package>",     // same value as bundleId
 *       "platform": "android",
 *       "events": [ <SessionEvent JSON>, ... ]
 *     }
 *
 * The body is HMAC-signed exactly like a collect (same `${ts}.<body>`
 * canonical form) so the server's signature middleware accepts both
 * routes from the same code path.
 *
 * Failures are logged but never retried — session events are
 * best-effort and the next batch will land. Hammering the server with
 * retries on a flaky cell tower would burn quota with little benefit.
 */
internal class SessionEventClient(private val config: ClickTrustConfig) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClickTrust-SessionEvents").apply { isDaemon = true }
    }

    fun send(sessionId: String, events: List<SessionEvent>, bundleId: String) {
        if (events.isEmpty()) return
        executor.execute { sendBlocking(sessionId, events, bundleId) }
    }

    private fun sendBlocking(sessionId: String, events: List<SessionEvent>, bundleId: String) {
        val arr = JSONArray()
        for (e in events) arr.put(e.toJson())
        val root = JSONObject().apply {
            put("sessionId", sessionId)
            put("tid", config.trackingId)
            // Emit both keys — server's Android identity check reads
            // `packageName`, but `bundleId` stays for parity with iOS
            // and the JS snippet. Same value, two keys.
            put("bundleId", bundleId)
            put("packageName", bundleId)
            put("platform", "android")
            put("events", arr)
        }
        val bodyBytes = root.toString().toByteArray(Charsets.UTF_8)
        val signed = HmacSigner.sign(config.sdkSecret, bodyBytes)
        val headers = mapOf(
            "X-CT-Tracking-Id" to config.trackingId,
            "X-CT-Bundle-Id" to bundleId,
            "X-CT-Platform" to "android",
            "X-CT-Timestamp" to signed.timestampHeader,
            "X-CT-Signature" to signed.signatureHeader,
        )
        val response = HttpClient.postJson(
            url = "${config.apiBase}/api/session-events",
            body = bodyBytes,
            headers = headers,
        )
        if (response.status !in 200..299) {
            Logger.d("SessionEventClient non-2xx: ${response.status}")
        }
    }

    fun shutdown() {
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }
}
