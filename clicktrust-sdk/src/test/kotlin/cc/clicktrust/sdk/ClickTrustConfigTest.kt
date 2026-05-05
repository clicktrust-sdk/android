package cc.clicktrust.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Construction-time validation contract for [ClickTrustConfig].
 *
 * These are the same rules applied by the iOS [ClickTrustConfiguration.validated]
 * — both implementations should accept and reject the same inputs so a
 * shared docs page can describe one set of rules.
 */
class ClickTrustConfigTest {

    private val validSecret = "0123456789ABCDEF0123456789ABCDEF" // 32 chars

    @Test
    fun valid_config_normalises_trailing_slash() {
        val cfg = ClickTrustConfig(
            trackingId = "tid12345",
            apiBase = "https://app.clicktrust.cc/",
            sdkSecret = validSecret,
        ).validate()
        assertEquals("https://app.clicktrust.cc", cfg.apiBase)
    }

    @Test
    fun rejects_short_tracking_id() {
        try {
            ClickTrustConfig("abc", "https://x.dev", validSecret).validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun rejects_short_secret() {
        try {
            ClickTrustConfig("tid12345", "https://x.dev", "tooshort").validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun rejects_non_http_scheme() {
        try {
            ClickTrustConfig("tid12345", "ftp://x.dev", validSecret).validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun accepts_http_for_local_dev() {
        val cfg = ClickTrustConfig("tid12345", "http://10.0.2.2:5000", validSecret).validate()
        assertEquals("http://10.0.2.2:5000", cfg.apiBase)
    }

    @Test
    fun rejects_silly_batch_size() {
        try {
            ClickTrustConfig("tid12345", "https://x.dev", validSecret, maxEventsPerBatch = 5_000).validate()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
