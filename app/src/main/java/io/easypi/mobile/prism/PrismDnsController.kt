package io.easypi.mobile.prism

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

object PrismDnsController {
    // Kept apart from the VPN runtime switches, which are cleared whenever a
    // new service instance starts: the chosen mode is a lasting setting.
    private const val MODE_PREFERENCES = "easypi_protection_mode"
    private const val KEY_STRICT = "strict_home_tunnel"

    fun needsVpnPermission(context: Context) = VpnService.prepare(context)

    /** Strict: all traffic through the home EasyPi. Normal: filtered on the phone. */
    fun isStrict(context: Context): Boolean =
        context.getSharedPreferences(MODE_PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_STRICT, false)

    fun setStrict(context: Context, strict: Boolean) {
        context.getSharedPreferences(MODE_PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STRICT, strict).apply()
        // A running Protection switches transport at once.
        if (isRunning()) {
            context.startService(
                Intent(context, PrismDnsVpnService::class.java).setAction(PrismDnsVpnService.ACTION_RESTART_TRANSPORT),
            )
        }
    }

    fun start(context: Context) {
        PrismDnsVpnService.prepareForStart()
        ContextCompat.startForegroundService(
            context,
            Intent(context, PrismDnsVpnService::class.java).setAction(PrismDnsVpnService.ACTION_ENABLE_PROTECTION),
        )
    }

    fun stop(context: Context) {
        PrismDnsVpnService.shutdownInProgress.set(true)
        context.startService(
            Intent(context, PrismDnsVpnService::class.java).setAction(PrismDnsVpnService.ACTION_DISABLE_PROTECTION),
        )
    }

    fun isRunning(): Boolean = PrismDnsVpnService.isProtectionRunning
    fun stopAll(context: Context) {
        context.startService(Intent(context, PrismDnsVpnService::class.java).setAction(PrismDnsVpnService.ACTION_STOP_ALL))
    }
    fun isStarting(): Boolean = PrismDnsVpnService.startupInProgress.get()
    fun isStopping(): Boolean = PrismDnsVpnService.shutdownInProgress.get()
    fun lastStartError(): String? = PrismDnsVpnService.lastStartError
}
