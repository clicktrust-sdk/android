package cc.clicktrust.sdk.models

import org.json.JSONObject

/**
 * Server response from `POST /api/collect`.
 *
 * The shape mirrors what the JS snippet receives so the analyzer +
 * downstream pipelines speak one language. Decoding is tolerant: any
 * field the server doesn't send becomes the obvious null/empty value
 * here so a missing key never crashes the SDK.
 *
 * `action` drives client-side behavior:
 *   - `"allow"`     — do nothing
 *   - `"challenge"` — present the captcha overlay
 *   - `"block"`     — present the block screen (no challenge); halt
 *                     any user-facing flow until `unblock` returns
 *   - `"shadow"`    — record but never show; used for testing rules
 */
public data class Verdict(
    public val action: Action,
    public val score: Double,
    public val reasons: List<String>,
    public val block: BlockDirective?,
) {
    public enum class Action(internal val wire: String) {
        ALLOW("allow"),
        CHALLENGE("challenge"),
        BLOCK("block"),
        SHADOW("shadow"),
        UNKNOWN("unknown"),
        ;
        internal companion object {
            fun fromWire(s: String?): Action =
                values().firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: UNKNOWN
        }
    }

    internal companion object {
        fun fromJson(o: JSONObject): Verdict {
            val action = Action.fromWire(o.optString("action", "allow"))
            val score = o.optDouble("score", 0.0)
            val reasons = o.optJSONArray("reasons")?.let { arr ->
                List(arr.length()) { i -> arr.optString(i, "") }.filter { it.isNotEmpty() }
            } ?: emptyList()
            val block = o.optJSONObject("block")?.let(BlockDirective.Companion::fromJson)
            return Verdict(action, score, reasons, block)
        }
    }
}

/**
 * Optional supplementary instructions the server can attach to a
 * `block` or `challenge` verdict — e.g. PoW difficulty override or a
 * pre-baked challenge token to use instead of generating one locally.
 */
public data class BlockDirective(
    public val token: String?,
    public val powDifficultyBits: Int?,
    public val message: String?,
    public val targetColor: String?,
) {
    internal companion object {
        fun fromJson(o: JSONObject): BlockDirective = BlockDirective(
            token = o.optString("token", "").ifEmpty { null },
            powDifficultyBits = if (o.has("powDifficultyBits")) o.optInt("powDifficultyBits") else null,
            message = o.optString("message", "").ifEmpty { null },
            targetColor = o.optString("targetColor", "").ifEmpty { null },
        )
    }
}
