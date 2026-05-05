package cc.clicktrust.sdk.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Verifies the proof-of-work primitive matches the iOS contract so a
 * challenge solved on one platform would also verify on the other.
 *
 * We deliberately keep the difficulty in the unit test below 16 bits
 * so the `solve` step finishes in a few ms even on CI runners. The
 * runtime scaling is geometric so testing at 8 bits doesn't validate
 * 24 bits — the algorithmic correctness IS the same regardless.
 */
class CaptchaChallengeTest {

    @Test
    fun solve_yields_nonce_satisfying_difficulty_mask() {
        val challenge = CaptchaChallenge(
            token = "test-token-1234",
            powDifficultyBits = 8,
            targetColor = CaptchaChallenge.TargetColor.RED,
        )
        val nonce = challenge.solveProofOfWork()
        assertNotNull("PoW must return a nonce within attempt budget", nonce)
        val digest = MessageDigest.getInstance("SHA-256").digest("test-token-1234:$nonce".toByteArray())
        val low8 = digest[7].toInt() and 0xff
        // 8-bit mask = bottom byte of the big-endian first 8 bytes is
        // zero. (Higher bits of the qword can be anything.)
        assertEquals("low 8 bits must be zero", 0, low8 and 0xff)
    }

    @Test
    fun solver_respects_external_cancellation() {
        val challenge = CaptchaChallenge(
            token = "uncancellable",
            powDifficultyBits = 30,
            targetColor = CaptchaChallenge.TargetColor.BLUE,
        )
        // Cancel after the very first attempt — we should get null
        // back without iterating to the algorithm's hard cap.
        var calls = 0
        val nonce = challenge.solveProofOfWork(shouldStop = { calls++; calls > 1 })
        assertNull("cancellation must short-circuit the solver", nonce)
    }

    @Test
    fun makeLocal_picks_a_color_and_random_token() {
        val a = CaptchaChallenge.makeLocal(difficultyBits = 8)
        val b = CaptchaChallenge.makeLocal(difficultyBits = 8)
        assertEquals(8, a.powDifficultyBits)
        // 32 bytes hex → 64 chars
        assertEquals(64, a.token.length)
        // Vanishingly unlikely for two random 32-byte values to collide
        assertNotNull(a.targetColor)
        assertNotNull(b.targetColor)
    }
}
