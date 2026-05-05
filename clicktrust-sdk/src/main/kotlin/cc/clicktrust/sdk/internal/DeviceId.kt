package cc.clicktrust.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Stable, privacy-respecting device identifier used in [CollectPayload.deviceIdHash].
 *
 * We hash `Settings.Secure.ANDROID_ID` with SHA-256. The raw value is
 * a per-app-install identifier on Android 8+, NOT the device IMEI or
 * MAC, so it doesn't require any sensitive permissions and it survives
 * app reinstalls under the same Google account when the user opts in.
 *
 * Rules:
 *  - Never log or transmit the raw value — only the hash.
 *  - Treat the hash as opaque; it has no semantic meaning to the
 *    server other than "is this the same device we saw before?"
 *  - On emulators ANDROID_ID can be the famous "9774d56d682e549c" —
 *    hash it anyway so emulator-farm detection downstream still works.
 *
 * The companion `hardwareIdentifier()` returns a coarse hardware
 * descriptor used by the device-farm trap. Mirrors iOS' `uname -m`
 * call.
 */
internal object DeviceId {

    @SuppressLint("HardwareIds") // intentional: hashed before transmission, never logged
    fun hashed(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: ""
        if (raw.isEmpty()) return ""
        return sha256Hex(raw)
    }

    /**
     * Coarse hardware fingerprint composed from public Build.* fields.
     * Used by the server to cluster identical-hardware sessions
     * (device-farm detection). NEVER includes any unique-to-instance
     * data (no serial, no IMEI) — purely the model class.
     */
    fun hardwareIdentifier(): String = listOf(
        Build.BRAND,
        Build.MANUFACTURER,
        Build.MODEL,
        Build.HARDWARE,
        Build.PRODUCT,
        Build.BOARD,
        Build.DEVICE,
    ).joinToString(separator = "|") { it.orEmpty() }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(((b.toInt() and 0xff) shr 4).toString(16))
            sb.append((b.toInt() and 0x0f).toString(16))
        }
        return sb.toString()
    }
}
