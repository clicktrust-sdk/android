package cc.clicktrust.sdk.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cross-language compatibility tests for [HmacSigner].
 *
 * The intent here is to nail the exact canonical form the server's
 * `verifySdkSignature` function expects so that we get a fast,
 * platform-agnostic regression signal if anyone tweaks the formatting.
 * We re-derive the expected hex value with a vanilla javax.crypto Mac
 * call so a test failure points at our framing logic rather than the
 * underlying primitive.
 */
class HmacSignerTest {

    private val secret = "verysecretverysecretverysecret_32"
    private val body = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)
    private val timestamp = 1_700_000_000_000L

    @Test
    fun signed_headers_carry_timestamp_and_lowercase_sha256_hex() {
        val signed = HmacSigner.sign(secret, body, timestamp)

        assertEquals(timestamp.toString(), signed.timestampHeader)
        assertTrue("must be sha256= prefixed: ${signed.signatureHeader}", signed.signatureHeader.startsWith("sha256="))

        val hexPart = signed.signatureHeader.removePrefix("sha256=")
        assertEquals("64 lowercase hex chars expected", 64, hexPart.length)
        assertEquals(hexPart.lowercase(), hexPart)
    }

    @Test
    fun signature_matches_canonical_timestamp_dot_body_form() {
        val signed = HmacSigner.sign(secret, body, timestamp)
        val canonical = "$timestamp.${String(body, Charsets.UTF_8)}".toByteArray(Charsets.UTF_8)
        val expected = expectedHex(secret, canonical)

        assertEquals("sha256=$expected", signed.signatureHeader)
    }

    @Test
    fun changing_one_byte_of_body_changes_signature() {
        val a = HmacSigner.sign(secret, body, timestamp)
        val mutated = body.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val b = HmacSigner.sign(secret, mutated, timestamp)
        assertNotEquals(a.signatureHeader, b.signatureHeader)
    }

    @Test
    fun changing_timestamp_changes_signature_even_for_same_body() {
        val a = HmacSigner.sign(secret, body, timestamp)
        val b = HmacSigner.sign(secret, body, timestamp + 1)
        assertNotEquals(a.signatureHeader, b.signatureHeader)
    }

    private fun expectedHex(key: String, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(message)
        val sb = StringBuilder(out.size * 2)
        for (b in out) {
            sb.append(((b.toInt() and 0xff) shr 4).toString(16))
            sb.append((b.toInt() and 0x0f).toString(16))
        }
        return sb.toString()
    }
}
