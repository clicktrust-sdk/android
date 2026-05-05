package cc.clicktrust.sdk.recorder

import android.os.Handler
import android.os.HandlerThread
import cc.clicktrust.sdk.ClickTrustConfig
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.SessionEvent
import cc.clicktrust.sdk.transport.SessionEventClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Buffers [SessionEvent]s and flushes them to `/api/session-events`
 * either when the buffer reaches [ClickTrustConfig.maxEventsPerBatch]
 * or every [ClickTrustConfig.flushIntervalMs] milliseconds — whichever
 * comes first. Mirrors the iOS [SessionRecorder] behaviour.
 *
 * All buffer manipulation runs on a dedicated [HandlerThread] named
 * `ClickTrust-Recorder` so:
 *   1. The main thread never serializes events.
 *   2. Concurrent calls from gesture capture (main) and lifecycle
 *      hooks (main) and manual `record(...)` calls from any worker
 *      are funnelled through one queue, eliminating the need for
 *      lock-step synchronisation on the buffer itself.
 *
 * Sessions are identified by a fresh UUID at [start]; an explicit
 * [start] also flushes whatever was buffered for the previous session
 * to avoid mixing event timelines.
 */
internal class SessionRecorder(
    private val config: ClickTrustConfig,
    private val client: SessionEventClient,
    private val bundleId: String,
) {
    private val thread = HandlerThread("ClickTrust-Recorder").apply { start() }
    private val handler = Handler(thread.looper)

    private val buffer = ArrayList<SessionEvent>()
    private val sessionStartedAt = AtomicLong(0L)

    @Volatile private var sessionId: String = UUID.randomUUID().toString()
    @Volatile private var running: Boolean = false

    fun start() {
        handler.post {
            // Flush any leftovers from a previous configure() call so
            // they don't bleed into the new session under a different
            // session id (the server uses session-id to bucket).
            flushLocked(reason = "session-restart")
            sessionId = UUID.randomUUID().toString()
            sessionStartedAt.set(System.currentTimeMillis())
            running = true
            scheduleFlush()
        }
    }

    fun stop() {
        running = false
        handler.post { flushLocked(reason = "stop") }
    }

    fun record(
        type: String,
        x: Int? = null,
        y: Int? = null,
        screen: String? = null,
        meta: Map<String, Any?>? = null,
    ) {
        if (!running) return
        val started = sessionStartedAt.get().let { if (it > 0L) it else System.currentTimeMillis() }
        val event = SessionEvent(
            t = System.currentTimeMillis() - started,
            type = type,
            x = x,
            y = y,
            screen = screen,
            meta = meta,
        )
        handler.post {
            buffer.add(event)
            if (buffer.size >= config.maxEventsPerBatch) {
                flushLocked(reason = "buffer-full")
            }
        }
    }

    /** Force a synchronous flush — used by lifecycle background hooks. */
    fun flushNow() {
        handler.post { flushLocked(reason = "manual") }
    }

    private fun scheduleFlush() {
        handler.postDelayed({
            if (running) {
                flushLocked(reason = "interval")
                scheduleFlush()
            }
        }, config.flushIntervalMs)
    }

    /**
     * MUST be called from the [handler] thread. Snapshots the
     * current buffer, clears it, and POSTs in the background. The
     * client's HTTP call itself runs on its own internal worker so
     * we don't block the recorder thread waiting for the network.
     */
    private fun flushLocked(reason: String) {
        if (buffer.isEmpty()) return
        val batch = ArrayList(buffer)
        buffer.clear()
        Logger.d("SessionRecorder flushing ${batch.size} events ($reason)")
        client.send(
            sessionId = sessionId,
            events = batch,
            bundleId = bundleId,
        )
    }
}
