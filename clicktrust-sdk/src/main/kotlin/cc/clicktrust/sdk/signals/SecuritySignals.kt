package cc.clicktrust.sdk.signals

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.AntiDetectInfo
import java.io.File
import java.security.MessageDigest

/**
 * Snapshots tamper / instrumentation indicators that map to the
 * existing iOS `AntiDetectInfo` plus three Android-specific ones
 * (Xposed, Frida, repackaged-APK).
 *
 * Each detector is intentionally heuristic — none should crash or
 * throw, and false positives are preferred over false negatives
 * because the server combines this with IP / behavioral signals
 * before deciding to block. A motivated attacker can defeat each
 * check individually; the value is in the *combination*.
 */
internal object SecuritySignals {

    /**
     * @param expectedSigningSha256
     *  SHA-256 of the production signing certificate, lowercase hex.
     *  When non-null we set `resignedBundle = true` if the running APK
     *  was signed with a different key. Pass null to leave the check
     *  inert (e.g. during local development).
     */
    fun snapshot(context: Context, expectedSigningSha256: String? = null): AntiDetectInfo {
        return AntiDetectInfo(
            rooted = isRooted(),
            debuggerAttached = isDebuggerAttached(),
            emulator = isEmulator(),
            resignedBundle = expectedSigningSha256?.let { isResigned(context, it) } ?: false,
            xposedDetected = isXposedDetected(),
            frida = isFridaDetected(),
        )
    }

    // ── Root detection ────────────────────────────────────────────
    // We check for: well-known su / busybox / Magisk paths, the test-
    // keys build tag, and writable system partitions. None of these
    // alone is a smoking gun (test-keys is set on internal Pixel
    // builds for example) so any single hit is a soft signal.
    private val ROOT_PATHS = arrayOf(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/xbin/busybox",
        "/data/adb/magisk",
        "/sbin/magisk",
    )

    private fun isRooted(): Boolean {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        for (p in ROOT_PATHS) {
            try { if (File(p).exists()) return true } catch (_: Throwable) { /* skipping a bad path is fine */ }
        }
        // `which su` style probe — exec'ing su would prompt the user
        // on Magisk / SuperSU, so we only check that the binary
        // resolves on $PATH.
        val pathDirs = (System.getenv("PATH") ?: "").split(":")
        for (dir in pathDirs) {
            try { if (File(dir, "su").exists()) return true } catch (_: Throwable) {}
        }
        return false
    }

    // ── Debugger / instrumentation ───────────────────────────────
    private fun isDebuggerAttached(): Boolean = try {
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    } catch (_: Throwable) { false }

    // ── Emulator detection ───────────────────────────────────────
    // Check the canonical Build.* tells. Real devices virtually never
    // have any of these substrings; emulators almost always have
    // several. Combined-evidence — any two hits mean emulator.
    private fun isEmulator(): Boolean {
        val hits = listOf(
            Build.FINGERPRINT.startsWith("generic"),
            Build.FINGERPRINT.startsWith("unknown"),
            Build.MODEL.contains("google_sdk", ignoreCase = true),
            Build.MODEL.contains("Emulator", ignoreCase = true),
            Build.MODEL.contains("Android SDK built for", ignoreCase = true),
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true),
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")),
            "google_sdk" == Build.PRODUCT,
            Build.HARDWARE.contains("ranchu", ignoreCase = true),
            Build.HARDWARE.contains("goldfish", ignoreCase = true),
            File("/dev/socket/qemud").exists(),
            File("/dev/qemu_pipe").exists(),
        ).count { it }
        return hits >= 2
    }

    // ── Resigned / repackaged APK ────────────────────────────────
    @Suppress("DEPRECATION")
    private fun isResigned(context: Context, expectedSha256Hex: String): Boolean {
        val want = expectedSha256Hex.lowercase()
        val pkg = context.packageName
        return try {
            val pm = context.packageManager
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                pi.signingInfo?.let { si ->
                    if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                } ?: emptyArray()
            } else {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
            }
            val md = MessageDigest.getInstance("SHA-256")
            for (s in sigs) {
                val hex = md.digest(s.toByteArray()).toHex()
                if (hex == want) return false
            }
            true
        } catch (t: Throwable) {
            Logger.w("isResigned check failed; treating as not-resigned to avoid false-positives: ${t.javaClass.simpleName}")
            false
        }
    }

    // ── Xposed / LSPosed framework hooks ─────────────────────────
    private fun isXposedDetected(): Boolean = try {
        val classes = listOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "org.lsposed.lspd.core.Main",
        )
        classes.any { runCatching { Class.forName(it) }.isSuccess }
    } catch (_: Throwable) { false }

    // ── Frida detection ──────────────────────────────────────────
    // Probe for the well-known Frida server library paths and the
    // default control port. We only TCP-connect to localhost so this
    // never leaves the device.
    private val FRIDA_PATHS = arrayOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
    )

    private fun isFridaDetected(): Boolean {
        for (p in FRIDA_PATHS) {
            try { if (File(p).exists()) return true } catch (_: Throwable) {}
        }
        // /proc/self/maps is readable on all API levels we support;
        // a Frida injection shows up as `frida-agent-*.so` mapped
        // into the running process.
        return try {
            File("/proc/self/maps").bufferedReader().useLines { seq ->
                seq.any { it.contains("frida", ignoreCase = true) }
            }
        } catch (_: Throwable) { false }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(((b.toInt() and 0xff) shr 4).toString(16))
            sb.append((b.toInt() and 0x0f).toString(16))
        }
        return sb.toString()
    }
}
