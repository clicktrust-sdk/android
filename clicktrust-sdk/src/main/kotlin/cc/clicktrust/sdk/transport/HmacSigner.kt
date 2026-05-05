package cc.clicktrust.sdk.transport

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signer that produces byte-identical signatures with the
 * iOS SDK and the JS snippet so the server's [verifySdkSignature] can
 * accept all three from one code path.
 *
 * Canonical form (do NOT change without a coordinated server release):
 *
 *     message = "${timestampMs}." + <raw POST body bytes>
 *     sig     = sha256=<lowercase hex of HMAC-SHA256(secret, message)>
 *
 * Where:
 *  - `timestampMs` is decimal ms-since-epoch (the same value sent in
 *    the `X-CT-Timestamp` header — the server enforces a ±5 minute
 *    window so client clocks within that range still verify).
 *  - `<raw POST body bytes>` is whatever bytes you actually wrote to
 *    the wire — the body is NOT re-canonicalized, so callers MUST
 *    sign and POST the *exact same* serialization. CollectPayload's
 *    `toJson()` is deterministic for this reason.
 *  - The "." between timestamp and body is a single ASCII period (no
 *    space, no JSON wrapping). Anything else and the server's
 *    HMAC-comparison fails closed and the request is rejected as a
 *    spoof.
 *
 * The `sha256=` prefix matches GitHub-style webhook signing — keep it
 * even though it's redundant in our case; the server includes it in
 * the constant-time comparison.
 */
internal object HmacSigner {

    /** Numeric timestamp + hex signature for one request. */
    internal data class SignedHeaders(val timestampHeader: String, val signatureHeader: String)

    fun sign(secret: String, body: ByteArray, timestampMs: Long = System.currentTimeMillis()): SignedHeaders {
        val prefix = "$timestampMs.".toByteArray(Charsets.UTF_8)
        val canonical = ByteArray(prefix.size + body.size)
        System.arraycopy(prefix, 0, canonical, 0, prefix.size)
        System.arraycopy(body, 0, canonical, prefix.size, body.size)

        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALG))
        val digest = mac.doFinal(canonical)
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(((b.toInt() and 0xff) shr 4).toString(16))
            hex.append((b.toInt() and 0x0f).toString(16))
        }
        return SignedHeaders(
            timestampHeader = timestampMs.toString(),
            signatureHeader = "sha256=$hex",
        )
    }

    private const val HMAC_ALG = "HmacSHA256"
}
