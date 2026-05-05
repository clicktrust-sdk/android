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
import cc.clicktrust.sdk.transport.CollectClient
import cc.clicktrust.sdk.transport.SessionEventClient
import java.util.concurrent.atomic.AtomicLong

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

    public const val VERSION: String = "1.0.0"

    @Volatile private var appContext: Context? = null
    @Volatile private var config: ClickTrustConfig? = null
    @Volatile private var collectClient: CollectClient? = null
    @Volatile private var sessionClient: SessionEventClient? = null
    @Volatile private var recorder: SessionRecorder? = null
    @Volatile private var gestureCapture: GestureCapture? = null
    @Volatile private var verdictHandler: ((Verdict) -> Unit)? = null
    @Volatile private var expectedSigningSha256: String? = null
    @Volatile private var lastChallengeProof: ChallengeProof? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastCollectAt = AtomicLong(0L)
    private const val COLLECT_MIN_INTERVAL_MS: Long = 5_000

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
    @AnyThread
    public fun configure(
        application: Application,
        config: ClickTrustConfig,
        expectedSigningCertSha256: String? = null,
    ) {
        val validated = config.validate()
        synchronized(this) {
            shutdownInternal()
            this.appContext = application.applicationContext
            this.config = validated
            this.expectedSigningSha256 = expectedSigningCertSha256
            Logger.debugEnabled = validated.debugLogging

            val collect = CollectClient(validated)
            val session = SessionEventClient(validated)
            val rec = SessionRecorder(
                config = validated,
                client = session,
                bundleId = application.packageName,
            )
            val gestures = GestureCapture(rec)

            this.collectClient = collect
            this.sessionClient = session
            this.recorder = rec
            this.gestureCapture = gestures

            LifecycleTracker.register(application)
            LifecycleTracker.configureCallbacks(
                onForeground = { internalCollect("foreground") },
                onBackground = { rec.flushNow() },
            )
            gestures.attach(application)
            rec.start()

            // Cold-start collect immediately so the very first verdict
            // is in hand by the time the user lands on the home
            // screen.
            internalCollect("cold_start")
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
        LifecycleTracker.configureCallbacks(null, null)
        recorder = null
        collectClient = null
        sessionClient = null
        gestureCapture = null
        config = null
        appContext = null
        verdictHandler = null
        expectedSigningSha256 = null
        lastChallengeProof = null
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
