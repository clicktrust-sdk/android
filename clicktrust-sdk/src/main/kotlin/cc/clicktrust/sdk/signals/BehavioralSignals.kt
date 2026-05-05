package cc.clicktrust.sdk.signals

import cc.clicktrust.sdk.models.BehavioralInfo
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters of user activity since the SDK started.
 *
 * Updated from many threads (gesture capture on the main thread,
 * lifecycle observer on the main thread, the recorder's serial queue)
 * so every counter is an atomic. Read-side only happens from the
 * collect builder which calls [snapshot] off the main thread; the
 * snapshot is a point-in-time copy so concurrent writes during a
 * snapshot just land in the next collect.
 *
 * Reset semantics: a `reset()` is intentionally NOT exposed from the
 * public API. The counters represent "since process start"; the
 * server is responsible for diffing successive collects when it wants
 * deltas. Resetting on every collect would lose data if a collect
 * fails to reach the server.
 */
internal object BehavioralSignals {

    private val tapCount = AtomicInteger(0)
    private val scrollCount = AtomicInteger(0)
    private val totalDwellMs = AtomicLong(0L)
    private val orientationChanges = AtomicInteger(0)
    private val foregroundCount = AtomicInteger(0)

    private var lastForegroundAt: Long = 0L

    fun recordTap() { tapCount.incrementAndGet() }
    fun recordScroll() { scrollCount.incrementAndGet() }
    fun recordOrientationChange() { orientationChanges.incrementAndGet() }

    /**
     * Called by the lifecycle observer when the app moves to the
     * foreground. We capture the wall clock so [recordBackground]
     * can attribute dwell time to the foreground span.
     */
    fun recordForeground() {
        foregroundCount.incrementAndGet()
        lastForegroundAt = System.currentTimeMillis()
    }

    fun recordBackground() {
        if (lastForegroundAt > 0L) {
            val delta = System.currentTimeMillis() - lastForegroundAt
            if (delta in 1..(24L * 3600_000L)) {
                // Cap at 24h to swallow clock-jump issues from devices
                // that NTP-correct between foreground and background.
                totalDwellMs.addAndGet(delta)
            }
            lastForegroundAt = 0L
        }
    }

    fun snapshot(): BehavioralInfo {
        // Account for an in-progress foreground span so the dwell
        // figure on a collect issued from the foreground reflects
        // current usage, not just *previous* foreground sessions.
        val activeDelta = if (lastForegroundAt > 0L) {
            (System.currentTimeMillis() - lastForegroundAt).coerceAtLeast(0L)
        } else 0L

        return BehavioralInfo(
            tapCount = tapCount.get(),
            scrollCount = scrollCount.get(),
            totalDwellMs = totalDwellMs.get() + activeDelta,
            orientationChanges = orientationChanges.get(),
            foregroundCount = foregroundCount.get(),
        )
    }
}
