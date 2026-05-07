package cc.clicktrust.sdk.transport

import cc.clicktrust.sdk.ClickTrustConfig
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.AppEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Buffered, batched poster for app-level events.
 *
 * Wire shape (matches `/api/app-events` on the server):
 *
 * ```
 * {
 *   "tid":          "<tracking id>",
 *   "bundleId":     "<package name>",
 *   "packageName":  "<package name>",
 *   "platform":     "android",
 *   "appVersion":   "1.4.2",
 *   "osVersion":    "14",
 *   "deviceModel":  "Pixel 8",
 *   "events": [
 *     {
 *       "name":          "purchase",
 *       "ts":            1714900000000,
 *       "amount":        99.99,
 *       "currency":      "USD",
 *       "contentId":     "sku_42",
 *       "contentType":   "product",
 *       "quantity":      1,
 *       "sessionId":     "<uuid>",
 *       "deviceIdHash":  "<hash>",
 *       "externalId":    "<idempotency uuid>",
 *       "source":        "sdk",
 *       "properties":    { "...": "..." }
 *     },
 *     ...
 *   ]
 * }
 * ```
 *
 * Body is HMAC-signed identically to /api/collect. Events are queued
 * in memory and flushed every 5 seconds (or when the queue hits 50
 * events, or when `flushNow()` is called from app-background). On
 * 4xx responses we drop the batch; on transient 5xx we keep it for
 * one retry. We deliberately don't persist the queue to disk —
 * a process kill mid-flight loses at most ~5 seconds of events,
 * which is the same loss profile as AppsFlyer's default config.
 */
internal class AppEventClient(private val config: ClickTrustConfig) {

    private val queue: LinkedBlockingQueue<AppEvent> = LinkedBlockingQueue()
    private val running = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ClickTrust-AppEvents").apply { isDaemon = true }
    }

    init {
        executor.scheduleWithFixedDelay({ runCatching { drain() } }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun enqueue(event: AppEvent) {
        if (!running.get()) return
        queue.offer(event)
        if (queue.size >= MAX_BATCH_SIZE) {
            executor.execute { runCatching { drain() } }
        }
    }

    fun flushNow() {
        executor.execute { runCatching { drain() } }
    }

    fun shutdown() {
        running.set(false)
        try { drain() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun drain() {
        if (queue.isEmpty()) return
        val batch = mutableListOf<AppEvent>()
        var ev = queue.poll()
        while (ev != null && batch.size < MAX_BATCH_SIZE) {
            batch.add(ev)
            ev = queue.poll()
        }
        if (batch.isEmpty()) return
        try {
            sendBlocking(batch)
        } catch (t: Throwable) {
            Logger.w("AppEventClient send failed; re-queuing ${batch.size} events", t)
            // Best-effort retry once — anything more is wasted bandwidth.
            for (b in batch) queue.offer(b)
        }
    }

    private fun sendBlocking(events: List<AppEvent>) {
        val arr = JSONArray()
        for (e in events) arr.put(e.toJson())
        // All events in a batch share the same bundle / app version
        // / OS / model — stamped by ClickTrust.trackEvent at enqueue
        // time. Hoisting them to top-level saves bytes and lets the
        // server's identity check read one place.
        val sample = events.first()
        val bundle = sample.bundleId.orEmpty()
        val root = JSONObject().apply {
            put("tid", config.trackingId)
            put("bundleId", bundle)
            put("packageName", bundle)
            put("platform", "android")
            sample.appVersion?.let { put("appVersion", it) }
            sample.osVersion?.let { put("osVersion", it) }
            sample.deviceModel?.let { put("deviceModel", it) }
            put("events", arr)
        }
        val bodyBytes = root.toString().toByteArray(Charsets.UTF_8)
        val signed = HmacSigner.sign(config.sdkSecret, bodyBytes)
        val headers = mapOf(
            "X-CT-Tracking-Id" to config.trackingId,
            "X-CT-Bundle-Id" to bundle,
            "X-CT-Platform" to "android",
            "X-CT-Timestamp" to signed.timestampHeader,
            "X-CT-Signature" to signed.signatureHeader,
        )
        val response = HttpClient.postJson(
            url = "${config.apiBase}/api/app-events",
            body = bodyBytes,
            headers = headers,
        )
        if (response.status !in 200..299) {
            Logger.d("AppEventClient non-2xx: ${response.status}")
            // 4xx = drop; 5xx = retry by throwing.
            if (response.status in 500..599) throw IllegalStateException("server ${response.status}")
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 5_000L
        const val MAX_BATCH_SIZE = 50
    }
}
