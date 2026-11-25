package io.easypi.mobile.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.pairing.PinnedHttps
import java.net.Inet6Address
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** Checks EasyPi through a physical network, explicitly bypassing any active VPN. */
object HomeNetworkDetector {
    fun isDirectlyReachable(context: Context, link: LinkedEasyPi): Boolean =
        directEndpoint(context, link) != null

    /** Returns the verified LAN EasyPi origin without ever falling back to Mobile Link. */
    fun directEndpoint(context: Context, link: LinkedEasyPi, requireIpv4Gateway: Boolean = false): String? {
        // A gateway's own valid public certificate is not the paired identity.
        if (link.certificateSha256.isNullOrBlank()) return null
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val physicalNetworks = manager.allNetworks
            .asSequence()
            .filter { network ->
                manager.getNetworkCapabilities(network)?.let { capabilities ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }
            .sortedWith(compareByDescending<android.net.Network> { network ->
                network == manager.activeNetwork
            }.thenByDescending { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            })
            .toList()
        val networks = if (requireIpv4Gateway) {
            physicalNetworks.filter { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } else physicalNetworks
        for (network in networks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            val gatewayCandidates = mutableListOf<String>()
            manager.getLinkProperties(network)?.routes
                ?.asSequence()
                ?.filter { it.isDefaultRoute }
                ?.mapNotNull { it.gateway }
                ?.filter { !requireIpv4Gateway || it is java.net.Inet4Address }
                ?.sortedBy { it is Inet6Address }
                ?.forEach { gateway ->
                    val host = gateway.hostAddress?.substringBefore('%') ?: return@forEach
                    gatewayCandidates += if (gateway is Inet6Address) "https://[$host]" else "https://$host"
                }
            val candidates = candidateOrigins(link.homeServerUrl, gatewayCandidates)
            for (candidate in candidates) {
                val direct = runCatching {
                    val connection = network.openConnection(URL("$candidate/api/v1/mobile/config")) as HttpsURLConnection
                    PinnedHttps.configure(connection, link.certificateSha256)
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 2_500
                    connection.readTimeout = 2_500
                    connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
                    connection.use {
                        if (it.responseCode !in 200..299) return@use false
                        val payload = JSONObject(it.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() })
                        payload.optString("connection_path") == "direct"
                    }
                }.getOrDefault(false)
                if (direct) return candidate
            }
        }
        return null
    }

    /** Prefer the QR-pinned installation address before probing network gateways. */
    internal fun candidateOrigins(homeServerUrl: String?, gateways: Iterable<String>): Set<String> =
        linkedSetOf<String>().apply {
            homeServerUrl?.takeIf(String::isNotBlank)?.let(::add)
            addAll(gateways)
        }

    private inline fun <T : HttpsURLConnection, R> T.use(block: (T) -> R): R =
        try { block(this) } finally { disconnect() }
}
