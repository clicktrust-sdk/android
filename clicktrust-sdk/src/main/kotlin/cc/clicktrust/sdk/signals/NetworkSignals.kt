package cc.clicktrust.sdk.signals

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import cc.clicktrust.sdk.models.ConnectionInfo

/**
 * Snapshots the network condition at the moment of a collect.
 *
 * The signals here directly drive the IP-defense and geo-mismatch
 * traps server-side:
 *  - `effectiveType` — wifi / cellular / none / unknown. Burst of
 *    cellular installs from rare carriers is a click-farm tell.
 *  - `carrier` — best-effort carrier name (no SIM = null). Used for
 *    cellular-cluster scoring.
 *  - `isVpn` — whether ConnectivityManager reports a VPN-capable
 *    transport. Combined with server-side IP analysis this beats
 *    pure-IP VPN detection on residential-VPN providers.
 *
 * All calls are guarded — a Pixel without a SIM, a tablet without a
 * radio, a system that returns `null` ConnectivityManager (rare,
 * happens on ARC++ Chromebooks) all degrade to safe defaults.
 */
internal object NetworkSignals {

    fun snapshot(context: Context): ConnectionInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val (effectiveType, isVpn) = readConnection(cm)
        val carrier = tm?.networkOperatorName?.takeIf { it.isNotBlank() }

        return ConnectionInfo(
            effectiveType = effectiveType,
            carrier = carrier,
            isVpn = isVpn,
        )
    }

    private fun readConnection(cm: ConnectivityManager?): Pair<String, Boolean> {
        if (cm == null) return "unknown" to false

        val active = cm.activeNetwork ?: return "none" to false
        val caps: NetworkCapabilities = cm.getNetworkCapabilities(active) ?: return "unknown" to false

        // VPN flag is independent of transport — a tunnel can run on
        // top of either Wi-Fi or cellular. Server scoring weights
        // these differently so report the underlying transport too.
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val effective = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "unknown"
        }
        return effective to isVpn
    }
}
