package io.easypi.mobile.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import io.easypi.mobile.ChameleonProfile
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.MobileApiClient
import io.easypi.mobile.prism.PrismDnsVpnService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import easypinative.Easypinative
import org.json.JSONObject

data class ChameleonStats(
    val connected: Boolean,
    val bytesSent: Long,
    val bytesReceived: Long,
)

object ChameleonController {
    fun needsVpnPermission(context: Context) = VpnService.prepare(context)

    fun prepareProfile(context: Context, link: LinkedEasyPi): ChameleonProfile {
        val enrollment = MobileApiClient.enrollChameleon(link)
        return ChameleonProfile(
            address = enrollment.address,
            serverAddress = enrollment.serverAddress,
            serverDialAddress = enrollment.serverDialAddress,
            serverDialAddress6 = enrollment.serverDialAddress6,
            pskKey = enrollment.pskKey,
            transportKey = enrollment.transportKey,
            realityServerPublicKey = enrollment.realityServerPublicKey,
            dohDomain = enrollment.dohDomain,
            bufferSize = enrollment.bufferSize,
            reconnectInterval = enrollment.reconnectInterval,
            mtu = enrollment.mtu,
        ).also(EasyPiLinkStore(context)::saveChameleonProfile)
    }

    fun start(context: Context) {
        PrismDnsVpnService.prepareForStart()
        ContextCompat.startForegroundService(
            context,
            Intent(context, PrismDnsVpnService::class.java)
                .setAction(PrismDnsVpnService.ACTION_ENABLE_CHAMELEON),
        )
    }

    /** Re-establishes the running transport, e.g. with a refreshed profile. */
    fun restart(context: Context) {
        context.startService(
            Intent(context, PrismDnsVpnService::class.java)
                .setAction(PrismDnsVpnService.ACTION_RESTART_TRANSPORT),
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, PrismDnsVpnService::class.java)
                .setAction(PrismDnsVpnService.ACTION_DISABLE_CHAMELEON),
        )
    }

    fun isRunning(): Boolean = PrismDnsVpnService.isChameleonRunning
    fun connectedAtMillis(): Long = PrismDnsVpnService.vpnConnectedAtMillis
    fun lastStartError(): String? = PrismDnsVpnService.lastStartError

    fun stats(): ChameleonStats = runCatching {
        val json = JSONObject(Easypinative.easyPiChameleonStats())
        ChameleonStats(
            connected = json.optBoolean("connected"),
            bytesSent = json.optLong("bytes_sent"),
            bytesReceived = json.optLong("bytes_recv"),
        )
    }.getOrDefault(ChameleonStats(false, 0, 0))

    internal fun config(profile: ChameleonProfile): String = buildJsonObject {
        put("server_address", profile.serverAddress)
        put("server_dial_address", profile.serverDialAddress)
        // Tried first: some mobile networks reach the VPS cleanly only over IPv6.
        if (profile.serverDialAddress6.isNotBlank()) put("server_dial_address6", profile.serverDialAddress6)
        put("psk_key", profile.pskKey)
        put("transport_key", profile.transportKey)
        put("protocol", "http2")
        put("reality_server_public_key", profile.realityServerPublicKey)
        put("reality_shield_preset", "ip")
        put("verify_server_certificate", true)
        if (profile.certificateSha256.isNotBlank()) {
            put("bootstrap_cert_sha256", profile.certificateSha256)
        }
        put("doh_domain", profile.dohDomain)
        put("buffer_size", profile.bufferSize)
        put("reconnect_interval", profile.reconnectInterval)
        put("fingerprint_mode", "mobile")
    }.toString()
}
