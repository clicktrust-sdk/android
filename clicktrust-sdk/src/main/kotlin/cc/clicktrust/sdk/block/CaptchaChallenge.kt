package cc.clicktrust.sdk.block

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Defines the proof-of-work + tap-target challenge presented to a
 * client when the server responds with `action: "challenge"` (or when
 * the SDK falls back to a locally-generated challenge for soft-block
 * scenarios while the server is unreachable).
 *
 * The protocol is bit-identical to the iOS `CaptchaChallenge` — the
 * server's [verifyCaptcha] middleware doesn't care which platform
 * solved the puzzle as long as the maths check out.
 *
 *   - PoW: find a `nonce` such that the first 8 bytes of
 *         SHA-256("$token:$nonce") interpreted big-endian as a UInt64
 *         have the lowest [powDifficultyBits] bits zero.
 *   - Tap target: user is shown five colored buttons and must tap
 *         [targetColor]. The colour palette is fixed across platforms
 *         so screenshots / UI tests can assert against constants.
 *
 * Difficulty defaults to 16 bits (~1-2s on a mid-range phone, ~50ms
 * on flagships). Server can override via [BlockDirective.powDifficultyBits].
 */
public data class CaptchaChallenge(
    public val token: String,
    public val powDifficultyBits: Int,
    public val targetColor: TargetColor,
) {

    public enum class TargetColor(internal val wire: String) {
        RED("red"), BLUE("blue"), GREEN("green"), YELLOW("yellow"), PURPLE("purple");
        internal companion object {
            fun fromWire(s: String?): TargetColor =
                values().firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: RED
        }
    }

    /**
     * Brute-force the PoW until a [nonce] satisfies the difficulty.
     *
     * Caller should run this off the main thread; on a 16-bit puzzle
     * a Pixel 4 averages ~150ms but the worst-case is unbounded
     * (2^bits * mean tries ≈ 2^bits / 2). Cancellation is cooperative
     * via [shouldStop].
     */
    internal fun solveProofOfWork(shouldStop: () -> Boolean = { false }): Long? {
        val mask = (1L shl powDifficultyBits.coerceIn(0, 30)) - 1L
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (!shouldStop()) {
            md.reset()
            md.update("$token:$nonce".toByteArray(Charsets.UTF_8))
            val digest = md.digest()
            // Big-endian read of the first 8 bytes as an unsigned long.
            // Long is signed in Java but bitwise ops give us the bit
            // pattern we want.
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (digest[i].toLong() and 0xff)
            }
            if ((v and mask) == 0L) return nonce
            nonce++
            // Defensive cap so a misconfigured difficulty (e.g. 30+
            // bits) doesn't run forever even if the caller forgets
            // to cancel.
            if (nonce > MAX_NONCE_ATTEMPTS) return null
        }
        return null
    }

    public companion object {
        private const val MAX_NONCE_ATTEMPTS = 50_000_000L
        private val SECURE_RNG = SecureRandom()

        /**
         * Build a locally-generated challenge as a fallback when the
         * server didn't pre-bake one. The token is 32 random bytes
         * hex-encoded so it can't be replayed.
         */
        @JvmStatic
        public fun makeLocal(difficultyBits: Int = 16): CaptchaChallenge {
            val raw = ByteArray(32).also(SECURE_RNG::nextBytes)
            val sb = StringBuilder(raw.size * 2)
            for (b in raw) {
                sb.append(((b.toInt() and 0xff) shr 4).toString(16))
                sb.append((b.toInt() and 0x0f).toString(16))
            }
            val palette = TargetColor.values()
            val target = palette[SECURE_RNG.nextInt(palette.size)]
            return CaptchaChallenge(token = sb.toString(), powDifficultyBits = difficultyBits, targetColor = target)
        }
    }
}
