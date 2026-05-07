package cc.clicktrust.sdk

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import cc.clicktrust.sdk.block.CaptchaChallenge
import cc.clicktrust.sdk.block.CaptchaOverlay
import cc.clicktrust.sdk.internal.LifecycleTracker
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.AntiDetectInfo
import cc.clicktrust.sdk.models.AppEvent
import cc.clicktrust.sdk.models.BehavioralInfo
import cc.clicktrust.sdk.models.ChallengeProof
import cc.clicktrust.sdk.models.CollectPayload
import cc.clicktrust.sdk.models.ConnectionInfo
import cc.clicktrust.sdk.models.Verdict
import cc.clicktrust.sdk.models.ViewabilityInfo
import cc.clicktrust.sdk.recorder.GestureCapture
import cc.clicktrust.sdk.recorder.SessionRecorder
import cc.clicktrust.sdk.signals.BehavioralSignals
import cc.clicktrust.sdk.signals.DeviceSignals
import cc.clicktrust.sdk.signals.NetworkSignals
import cc.clicktrust.sdk.signals.PrivacySignals
import cc.clicktrust.sdk.signals.SecuritySignals
import cc.clicktrust.sdk.signals.ViewabilitySignals
import cc.clicktrust.sdk.transport.AppEventClient
import cc.clicktrust.sdk.transport.CollectClient
import cc.clicktrust.sdk.transport.SessionEventClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Public façade for the ClickTrust Android SDK.
 *
 * Mirrors the iOS [ClickTrust] public class; methods named identically
 * behave identically. Singleton because the underlying signal
 * collectors and the lifecycle observer are inherently process-wide
 * (a process can only be in the foreground once).
 *
 * Typical wiring lives in `Application.onCreate`:
 *
 * ```kotlin
 * class MyApp : Application() {
 *   override fun onCreate() {
 *     super.onCreate()
 *     ClickTrust.configure(
 *       application = this,
 *       config = ClickTrustConfig(
 *         trackingId = BuildConfig.CLICKTRUST_TID,
 *         apiBase = "https://app.clicktrust.cc",
 *         sdkSecret = BuildConfig.CLICKTRUST_SDK_SECRET,
 *       ),
 *     )
 *     ClickTrust.onVerdict { verdict ->
 *       if (verdict.action == Verdict.Action.BLOCK) {
 *         // Hide your paywall, gate the screen, etc.
 *       }
 *     }
 *   }
 * }
 * ```
 */
public object ClickTrust {

    public const val VERSION: String = "1.1.0"

    @Volatile private var appContext: Context? = null
    @Volatile private var config: ClickTrustConfig? = null
    @Volatile private var collectClient: CollectClient? = null
    @Volatile private var sessionClient: SessionEventClient? = null
    @Volatile private var appEventClient: AppEventClient? = null
    @Volatile private var recorder: SessionRecorder? = null
    @Volatile private var gestureCapture: GestureCapture? = null
    @Volatile private var verdictHandler: ((Verdict) -> Unit)? = null
    @Volatile private var expectedSigningSha256: String? = null
    @Volatile private var lastChallengeProof: ChallengeProof? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var autoEventsEnabled: Boolean = true
    private val sessionStartedAt = AtomicLong(0L)
    private val lastForegroundAt = AtomicLong(0L)
    private val deviceIdHashRef = AtomicReference<String?>(null)
    private val sdkPreferences get() = appContext?.getSharedPreferences("clicktrust_sdk_state", Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCollectAt = AtomicLong(0L)
    private const val COLLECT_MIN_INTERVAL_MS: Long = 5_000

    /**
     * Idle window after which the next foreground transition fires a
     * fresh `session_start` auto-event. Matches AppsFlyer's default.
     */
    private const val SESSION_IDLE_THRESHOLD_MS: Long = 30_000

    private const val PREF_INSTALL_VERSION = "install_version"
    private const val PREF_LAST_VERSION = "last_version"
    private const val PREF_INSTALL_FIRED = "install_fired"

    // ── Configuration ────────────────────────────────────────────

    /**
     * Validates [config] and starts collect / session-event /
     * lifecycle / gesture pipelines. Re-calling reinitialises with
     * the new config — useful in tests but generally NOT something
     * production code should do once per app launch.
     *
     * Optional [expectedSigningCertSha256] turns on the resigned-
     * bundle detector. Pass the lowercase hex SHA-256 of your
     * production signing cert (`apksigner verify --print-certs`).
     */
    /**
     * Validates [config] and starts collect / session-event / lifecycle
     * / gesture pipelines. [autoEvents] (default `true`) enables
     * AppsFlyer-style auto firing of `install`, `first_open`,
     * `session_start`, and `update` so a partner who only calls
     * [configure] already gets install metrics with zero extra code.
     * Set false if you have a homegrown analytics layer and want full
     * control.
     */
    @AnyThread
    public fun configure(
        application: Application,
        config: ClickTrustConfig,
        expectedSigningCertSha256: String? = null,
        autoEvents: Boolean = true,
    ) {
        val validated = config.validate()
        synchronized(this) {
            shutdownInternal()
            this.appContext = application.applicationContext
            this.config = validated
            this.expectedSigningSha256 = expectedSigningCertSha256
            this.autoEventsEnabled = autoEvents
            this.sessionId = UUID.randomUUID().toString()
            this.sessionStartedAt.set(System.currentTimeMillis())
            Logger.debugEnabled = validated.debugLogging

            val collect = CollectClient(validated)
            val session = SessionEventClient(validated)
            val appEv = AppEventClient(validated)
            val rec = SessionRecorder(
                config = validated,
                client = session,
                bundleId = application.packageName,
            )
            val gestures = GestureCapture(rec)

            this.collectClient = collect
            this.sessionClient = session
            this.appEventClient = appEv
            this.recorder = rec
            this.gestureCapture = gestures

            LifecycleTracker.register(application)
            LifecycleTracker.configureCallbacks(
                onForeground = {
                    internalCollect("foreground")
                    onForegroundForEvents()
                },
                onBackground = {
                    rec.flushNow()
                    appEv.flushNow()
                    lastForegroundAt.set(System.currentTimeMillis())
                },
            )
            gestures.attach(application)
            rec.start()

            // Cold-start collect immediately so the very first verdict
            // is in hand by the time the user lands on the home
            // screen.
            internalCollect("cold_start")

            // Auto-events: install / first_open (once per install),
            // update (once per detected version change), session_start
            // (every fresh session). Cheap and deterministic.
            if (autoEvents) fireLifecycleAutoEvents(application)
        }
    }

    /** Convenience wrapper using the simpler argument order. */
    @AnyThread
    public fun configure(
        application: Application,
        trackingId: String,
        apiBase: String,
        sdkSecret: String,
    ): Unit = configure(
        application = application,
        config = ClickTrustConfig(trackingId = trackingId, apiBase = apiBase, sdkSecret = sdkSecret),
    )

    // ── Public hooks ─────────────────────────────────────────────

    /**
     * Set or clear the verdict callback. Always invoked on the main
     * thread, regardless of which thread the network completed on,
     * matching the iOS behaviour.
     */
    @AnyThread
    public fun onVerdict(handler: ((Verdict) -> Unit)?) {
        this.verdictHandler = handler
    }

    /**
     * Force a collect now. Throttled to one collect every 5s — the
     * server's analyzer doesn't gain meaningful new signal from
     * higher cadences and burning quota on rapid-fire collects is
     * pure waste.
     */
    @AnyThread
    public fun collectNow() {
        internalCollect("manual")
    }

    /**
     * Track an app-level named event (purchase, signup, level_complete, …).
     *
     * The simplest call mirrors AppsFlyer's API:
     * ```kotlin
     * ClickTrust.trackEvent(AppEvent.PURCHASE, mapOf("amount" to 99.99))
     * ```
     *
     * Standard event names live as constants on [AppEvent.Names] —
     * use them whenever you can so the server's pre-built dashboards
     * (purchase funnel, ad-funnel, signup-to-purchase) can group your
     * events. Custom names still record; they're just categorised as
     * `"custom"` and won't auto-roll-up.
     *
     * Revenue defaults: passing `amount` without `currency` lets the
     * server fall back to your account's default currency (USD unless
     * configured otherwise). Pass `currency` explicitly when you have
     * multi-currency reporting.
     *
     * Idempotency: pass `externalId` to make retries safe — the
     * server upserts on `(account_id, external_id)`. When omitted
     * the SDK auto-generates a UUID per call so each successful POST
     * counts exactly once.
     */
    @AnyThread
    public fun trackEvent(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        amount: Double? = null,
        currency: String? = null,
        contentId: String? = null,
        contentType: String? = null,
        quantity: Int? = null,
        externalId: String? = null,
    ) {
        enqueueEvent(
            AppEvent(
                name = name,
                ts = System.currentTimeMillis(),
                amount = amount,
                currency = currency,
                contentId = contentId,
                contentType = contentType,
                quantity = quantity,
                properties = properties,
                externalId = externalId ?: UUID.randomUUID().toString(),
                source = "sdk",
                sessionId = sessionId,
            ),
        )
    }

    /** Convenience overload — partner passes a fully-built [AppEvent]. */
    @AnyThread
    public fun trackEvent(event: AppEvent) {
        enqueueEvent(event.copy(
            externalId = event.externalId ?: UUID.randomUUID().toString(),
            sessionId = event.sessionId ?: sessionId,
        ))
    }

    /**
     * Hint that a particular view holds sensitive content. Currently a
     * no-op for the SDK's own collect (we don't capture screenshots),
     * but the value is stamped onto the View's `contentDescription`
     * with a `ct_masked_` prefix so future screenshot-based session
     * recording (Phase 4) and 3rd-party screen recorders can ignore
     * it.
     */
    @MainThread
    public fun maskField(view: android.view.View) {
        try {
            val cur = view.contentDescription?.toString().orEmpty()
            if (!cur.startsWith("ct_masked_")) {
                view.contentDescription = "ct_masked_${cur}"
            }
            view.setTag(0x4D41534B, "ct_masked")
        } catch (t: Throwable) {
            Logger.w("maskField failed", t)
        }
    }

    /**
     * Fully tear down. After [shutdown] you must call [configure]
     * again before any other method does anything useful. Safe to
     * call from any thread; safe to call repeatedly.
     */
    @AnyThread
    public fun shutdown() {
        synchronized(this) { shutdownInternal() }
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun shutdownInternal() {
        recorder?.stop()
        collectClient?.shutdown()
        sessionClient?.shutdown()
        appEventClient?.shutdown()
        LifecycleTracker.configureCallbacks(null, null)
        recorder = null
        collectClient = null
        sessionClient = null
        appEventClient = null
        gestureCapture = null
        config = null
        appContext = null
        verdictHandler = null
        expectedSigningSha256 = null
        lastChallengeProof = null
        sessionId = null
        autoEventsEnabled = true
        sessionStartedAt.set(0L)
        lastForegroundAt.set(0L)
        deviceIdHashRef.set(null)
    }

    // ── App events helpers ───────────────────────────────────────

    private fun enqueueEvent(event: AppEvent) {
        val ctx = appContext ?: return
        val client = appEventClient ?: return
        // Stamp identity context onto every event so the batch
        // poster can hoist them to top-level without having to peek
        // into Application again. We resolve deviceIdHash lazily and
        // cache it for the lifetime of the SDK process.
        val deviceIdHash = deviceIdHashRef.get() ?: run {
            val cfg = config
            val resolved = if (cfg != null) DeviceSignals.snapshot(ctx, cfg.trackingId).deviceIdHash else null
            deviceIdHashRef.set(resolved)
            resolved
        }
        val packageManager = runCatching { ctx.packageManager?.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val appVersion = packageManager?.versionName
        val osVersion = android.os.Build.VERSION.RELEASE
        val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

        val stamped = event.copy(
            deviceIdHash = event.deviceIdHash ?: deviceIdHash,
            bundleId = event.bundleId ?: ctx.packageName,
            appVersion = event.appVersion ?: appVersion,
            osVersion = event.osVersion ?: osVersion,
            deviceModel = event.deviceModel ?: deviceModel,
            sessionId = event.sessionId ?: sessionId,
        )
        client.enqueue(stamped)
    }

    private fun fireLifecycleAutoEvents(application: Application) {
        try {
            val prefs = sdkPreferences ?: return
            val pkg = application.packageName
            val pm = runCatching { application.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            val currentVersion = pm?.versionName ?: "unknown"

            val installFired = prefs.getBoolean(PREF_INSTALL_FIRED, false)
            if (!installFired) {
                // First-ever launch on this device. AppsFlyer fires both
                // `install` (one-time) AND `first_open` (one-time)
                // because some attribution networks subscribe to one but
                // not the other. We do the same.
                enqueueAutoEvent(AppEvent.INSTALL)
                enqueueAutoEvent(AppEvent.FIRST_OPEN)
                prefs.edit()
                    .putBoolean(PREF_INSTALL_FIRED, true)
                    .putString(PREF_INSTALL_VERSION, currentVersion)
                    .putString(PREF_LAST_VERSION, currentVersion)
                    .apply()
            } else {
                val lastVersion = prefs.getString(PREF_LAST_VERSION, null)
                if (lastVersion != null && lastVersion != currentVersion) {
                    enqueueAutoEvent(
                        AppEvent.UPDATE,
                        properties = mapOf("from" to lastVersion, "to" to currentVersion),
                    )
                }
                prefs.edit().putString(PREF_LAST_VERSION, currentVersion).apply()
            }
            // session_start fires every time configure() runs (i.e. every
            // process launch). Foreground re-entry within 30s is treated
            // as the same session — see onForegroundForEvents.
            enqueueAutoEvent(AppEvent.SESSION_START)
        } catch (t: Throwable) {
            Logger.w("Auto-event fire failed", t)
        }
    }

    private fun onForegroundForEvents() {
        if (!autoEventsEnabled) return
        val now = System.currentTimeMillis()
        val last = lastForegroundAt.getAndSet(now)
        // Fresh session if the app was backgrounded for >30s.
        if (last > 0 && now - last > SESSION_IDLE_THRESHOLD_MS) {
            sessionId = UUID.randomUUID().toString()
            sessionStartedAt.set(now)
            enqueueAutoEvent(AppEvent.SESSION_START)
        }
    }

    private fun enqueueAutoEvent(name: String, properties: Map<String, Any?> = emptyMap()) {
        enqueueEvent(
            AppEvent(
                name = name,
                ts = System.currentTimeMillis(),
                properties = properties,
                externalId = UUID.randomUUID().toString(),
                source = "auto",
                sessionId = sessionId,
            ),
        )
    }

    private fun internalCollect(reason: String) {
        val ctx = appContext ?: return
        val cfg = config ?: return
        val client = collectClient ?: return
        val now = System.currentTimeMillis()
        val last = lastCollectAt.get()
        // Cold-start + foreground always pass — explicit user-facing
        // reasons that need fresh verdicts. Manual / internal calls
        // are throttled.
        val bypassThrottle = reason == "cold_start" || reason == "foreground" || reason == "after_challenge"
        if (!bypassThrottle && now - last < COLLECT_MIN_INTERVAL_MS) return
        if (!lastCollectAt.compareAndSet(last, now)) return

        val payload = buildPayload(ctx, cfg)
        Logger.d("Collecting ($reason) tid=${cfg.trackingId} bundle=${payload.bundleId}")
        client.send(payload) { verdict ->
            if (verdict != null) handleVerdict(verdict)
        }
    }

    private fun buildPayload(ctx: Context, cfg: ClickTrustConfig): CollectPayload {
        val device = DeviceSignals.snapshot(ctx, cfg.trackingId)
        val connection: ConnectionInfo = NetworkSignals.snapshot(ctx)
        val behavioral: BehavioralInfo = BehavioralSignals.snapshot()
        val antiDetect: AntiDetectInfo = SecuritySignals.snapshot(ctx, expectedSigningSha256)
        val viewability: ViewabilityInfo = ViewabilitySignals.snapshot(ctx)
        val proof = lastChallengeProof
        if (proof != null) lastChallengeProof = null
        return CollectPayload(
            tid = device.tid,
            bundleId = device.bundleId,
            appVersion = device.appVersion,
            osVersion = device.osVersion,
            deviceModel = device.deviceModel,
            deviceIdHash = device.deviceIdHash,
            ua = device.ua,
            lang = device.lang,
            langs = device.langs,
            timezone = device.timezone,
            timezoneOffset = device.timezoneOffset,
            screenW = device.screenW,
            screenH = device.screenH,
            hardwareConcurrency = device.hardwareConcurrency,
            deviceMemory = device.deviceMemory,
            doNotTrack = PrivacySignals.doNotTrack(ctx),
            url = null,
            referer = null,
            timestamp = System.currentTimeMillis(),
            connection = connection,
            behavioral = behavioral,
            antiDetect = antiDetect,
            viewability = viewability,
            challenge = proof,
        )
    }

    private fun handleVerdict(verdict: Verdict) {
        // Hand off to consumer's handler first so they can update UI
        // before we present the captcha — they may want to dim a
        // background view, log analytics, etc.
        mainHandler.post { runCatching { verdictHandler?.invoke(verdict) } }

        if (verdict.action == Verdict.Action.CHALLENGE && config?.presentCaptchaAutomatically == true) {
            val directive = verdict.block
            val challenge = if (directive?.token != null) {
                CaptchaChallenge(
                    token = directive.token,
                    powDifficultyBits = directive.powDifficultyBits ?: 16,
                    targetColor = CaptchaChallenge.TargetColor.fromWire(directive.targetColor),
                )
            } else {
                CaptchaChallenge.makeLocal(difficultyBits = directive?.powDifficultyBits ?: 16)
            }
            CaptchaOverlay(
                challenge = challenge,
                message = directive?.message,
                onCompleted = { proof ->
                    if (proof != null) {
                        lastChallengeProof = proof
                        internalCollect("after_challenge")
                    }
                },
            ).present()
        }
    }
}
