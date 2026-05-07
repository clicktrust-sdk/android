package cc.clicktrust.sdk.models

import org.json.JSONObject

/**
 * App-level named event posted to `/api/app-events`.
 *
 * Modelled after AppsFlyer's event spec so partners migrating from
 * AppsFlyer / Adjust / Firebase Analytics get one-to-one mapping for
 * standard names (install, first_open, session_start, login, signup,
 * view_item, add_to_cart, purchase, ad_view, level_complete, …).
 *
 * Custom names (anything not in the canonical list) still record;
 * they just don't aggregate into the pre-built dashboards. The
 * server's `APP_EVENT_BY_NAME` map handles alias normalisation
 * (e.g. "first_launch" -> "first_open").
 *
 * The wire shape mirrors the iOS / Flutter equivalents byte-for-byte
 * so any one of the three platforms can be debugged with the same
 * server logs.
 */
public data class AppEvent(
    /** Canonical or custom event name (snake_case recommended). */
    val name: String,
    /** Client-side event time (ms since epoch). */
    val ts: Long = System.currentTimeMillis(),
    /** Optional revenue. Server defaults `currency` to USD when amount is set and currency is null. */
    val amount: Double? = null,
    val currency: String? = null,
    val contentType: String? = null,
    val contentId: String? = null,
    val quantity: Int? = null,
    /** Free-form bag of additional properties (e.g. orderId, plan, level). */
    val properties: Map<String, Any?> = emptyMap(),
    /**
     * Idempotency key. SDK auto-generates a UUID when the partner
     * doesn't provide one — guarantees retries don't double-count.
     */
    val externalId: String? = null,
    /** "sdk" (manual partner call) | "auto" (SDK lifecycle) | "server" */
    val source: String = "sdk",
    /** Optional session id — paired against /api/collect's session for cross-event analytics. */
    val sessionId: String? = null,
    /** SDK-supplied identity context (set automatically by ClickTrust.trackEvent). */
    val deviceIdHash: String? = null,
    val bundleId: String? = null,
    val appVersion: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("ts", ts)
        amount?.let { put("amount", it) }
        currency?.let { put("currency", it) }
        contentType?.let { put("contentType", it) }
        contentId?.let { put("contentId", it) }
        quantity?.let { put("quantity", it) }
        externalId?.let { put("externalId", it) }
        source.let { put("source", it) }
        sessionId?.let { put("sessionId", it) }
        deviceIdHash?.let { put("deviceIdHash", it) }
        bundleId?.let { put("bundleId", it) }
        appVersion?.let { put("appVersion", it) }
        osVersion?.let { put("osVersion", it) }
        deviceModel?.let { put("deviceModel", it) }
        if (properties.isNotEmpty()) {
            val p = JSONObject()
            for ((k, v) in properties) p.put(k, v ?: JSONObject.NULL)
            put("properties", p)
        }
    }

    /**
     * Canonical event names that match the server's standard event
     * catalog. Use these constants instead of free-form strings so
     * typos don't end up creating a new "category=custom" event.
     */
    public companion object Names {
        // Lifecycle
        public const val INSTALL: String = "install"
        public const val FIRST_OPEN: String = "first_open"
        public const val SESSION_START: String = "session_start"
        public const val UPDATE: String = "update"
        // Engagement
        public const val LOGIN: String = "login"
        public const val SIGNUP: String = "signup"
        public const val TUTORIAL_COMPLETE: String = "tutorial_complete"
        public const val SEARCH: String = "search"
        public const val SHARE: String = "share"
        public const val RATE: String = "rate"
        // Ecommerce
        public const val VIEW_ITEM: String = "view_item"
        public const val ADD_TO_CART: String = "add_to_cart"
        public const val ADD_TO_WISHLIST: String = "add_to_wishlist"
        public const val BEGIN_CHECKOUT: String = "begin_checkout"
        public const val ADD_PAYMENT_INFO: String = "add_payment_info"
        public const val PURCHASE: String = "purchase"
        public const val REFUND: String = "refund"
        public const val SUBSCRIBE: String = "subscribe"
        public const val TRIAL_START: String = "trial_start"
        // Ads
        public const val AD_VIEW: String = "ad_view"
        public const val AD_CLICK: String = "ad_click"
        public const val AD_REWARD: String = "ad_reward"
        // Gaming
        public const val LEVEL_START: String = "level_start"
        public const val LEVEL_COMPLETE: String = "level_complete"
        public const val ACHIEVEMENT_UNLOCKED: String = "achievement_unlocked"
        public const val SPEND_CREDITS: String = "spend_credits"
        // Content
        public const val CONTENT_VIEW: String = "content_view"
        public const val CONTENT_COMPLETE: String = "content_complete"
    }
}
