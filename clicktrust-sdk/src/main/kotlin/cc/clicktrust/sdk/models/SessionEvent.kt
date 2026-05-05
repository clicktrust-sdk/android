package cc.clicktrust.sdk.models

import org.json.JSONObject

/**
 * One row in the session-replay batch posted to `/api/session-events`.
 *
 * Wire keys are short on purpose — a well-engaged user can produce
 * thousands of events per session and every byte counts when they're
 * paying for cellular. Fields:
 *   - `t`      monotonic ms-since-session-start (NOT epoch)
 *   - `type`   well-known string: tap | scroll | screen_view | orient |
 *              foreground | background | masked_focus
 *   - `x`,`y`  optional touch coordinates in screen pixels
 *   - `screen` optional human-readable screen / activity / fragment name
 *   - `meta`   optional free-form bag of trap-relevant context
 *
 * The shape mirrors the iOS `SessionEvent` so both platforms produce
 * one Brevo-compatible session-replay timeline that the dashboard can
 * render with no per-platform branches.
 */
internal data class SessionEvent(
    val t: Long,
    val type: String,
    val x: Int? = null,
    val y: Int? = null,
    val screen: String? = null,
    val meta: Map<String, Any?>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", t)
        put("type", type)
        x?.let { put("x", it) }
        y?.let { put("y", it) }
        screen?.let { put("screen", it) }
        meta?.let {
            val nested = JSONObject()
            for ((k, v) in it) nested.put(k, v ?: JSONObject.NULL)
            put("meta", nested)
        }
    }
}
