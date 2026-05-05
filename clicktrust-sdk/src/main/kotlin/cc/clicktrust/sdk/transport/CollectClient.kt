package cc.clicktrust.sdk.transport

import cc.clicktrust.sdk.ClickTrustConfig
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.CollectPayload
import cc.clicktrust.sdk.models.Verdict
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Sends a single collect to `/api/collect`.
 *
 * Threading: every send hops to a single-threaded background executor
 * so the caller (often the main thread on cold-start / foreground)
 * never blocks on the network. Completion callbacks fire on the
 * executor — the public `ClickTrust.onVerdict` handler in turn marshals
 * to the main thread.
 *
 * Retry policy: NONE. The collect endpoint is idempotent in spirit
 * but the server is the source of truth on whether to charge a
 * collect against the partner's quota; retrying on transient errors
 * would inflate counts. The server's accept-all behaviour for known
 * tids means a transient failure just delays the next verdict to the
 * next foreground / explicit `collectNow()`.
 */
internal class CollectClient(private val config: ClickTrustConfig) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ClickTrust-Collect").apply { isDaemon = true }
    }

    fun send(payload: CollectPayload, callback: (Verdict?) -> Unit) {
        executor.execute {
            val verdict = try { sendBlocking(payload) } catch (t: Throwable) {
                Logger.w("CollectClient.send threw", t)
                null
            }
            try { callback(verdict) } catch (t: Throwable) {
                Logger.w("CollectClient callback threw", t)
            }
        }
    }

    private fun sendBlocking(payload: CollectPayload): Verdict? {
        val bodyJson = payload.toJson()
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        val signed = HmacSigner.sign(config.sdkSecret, bodyBytes)
        val headers = mapOf(
            "X-CT-Tracking-Id" to config.trackingId,
            "X-CT-Bundle-Id" to payload.bundleId,
            "X-CT-Platform" to "android",
            "X-CT-Timestamp" to signed.timestampHeader,
            "X-CT-Signature" to signed.signatureHeader,
        )
        val response = HttpClient.postJson(
            url = "${config.apiBase}/api/collect",
            body = bodyBytes,
            headers = headers,
        )
        if (response.status !in 200..299) {
            Logger.d("CollectClient non-2xx: ${response.status}")
            return null
        }
        return runCatching { Verdict.fromJson(JSONObject(response.body)) }
            .onFailure { if (it is JSONException) Logger.w("Bad verdict JSON: ${it.message}") }
            .getOrNull()
    }

    fun shutdown() {
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }
}
