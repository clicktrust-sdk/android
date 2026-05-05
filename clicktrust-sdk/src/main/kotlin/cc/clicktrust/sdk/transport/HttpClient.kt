package cc.clicktrust.sdk.transport

import cc.clicktrust.sdk.internal.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Tiny shared HTTP client used by [CollectClient] and
 * [SessionEventClient]. Built on `HttpURLConnection` so we don't drag
 * OkHttp + Okio (~750 KiB combined) into every consumer.
 *
 * Features kept intentionally minimal:
 *  - POST only; the SDK never reads via GET
 *  - JSON request body (UTF-8)
 *  - Custom headers from caller (used for HMAC signature + tracking id)
 *  - 10s connect timeout, 15s read timeout — enough for cellular,
 *    short enough that a hung server doesn't stall the recorder
 *  - Decompresses gzip responses transparently
 *  - Returns raw status + body so callers can interpret per-route
 */
internal object HttpClient {

    internal data class Response(val status: Int, val body: String)

    fun postJson(url: String, body: ByteArray, headers: Map<String, String>): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Content-Length", body.size.toString())
            for ((k, v) in headers) setRequestProperty(k, v)
        }

        return try {
            connection.outputStream.use { it.write(body) }
            val status = try { connection.responseCode } catch (e: IOException) { -1 }
            // 4xx + 5xx responses come from getErrorStream; success
            // codes from getInputStream. Either may be gzip'd if
            // the server's compressor is enabled.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = if (stream != null) {
                val gzip = connection.contentEncoding?.equals("gzip", ignoreCase = true) == true
                val reader = if (gzip) BufferedReader(InputStreamReader(GZIPInputStream(stream)))
                             else BufferedReader(InputStreamReader(stream))
                reader.use { it.readText() }
            } else ""
            Response(status, text)
        } catch (t: Throwable) {
            Logger.w("HTTP POST $url failed: ${t.javaClass.simpleName} ${t.message}")
            Response(-1, "")
        } finally {
            try { connection.disconnect() } catch (_: Throwable) {}
        }
    }
}
