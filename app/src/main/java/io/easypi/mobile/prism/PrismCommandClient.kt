package io.easypi.mobile.prism

import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.pairing.PinnedHttps
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject

data class PrismFlowCommand(
    val id: String,
    val clientIp: String,
    val clientPort: Int,
    val serverIp: String,
    val serverPort: Int,
    val protocol: String,
    val expiresAtSeconds: Double,
)

object PrismCommandClient {
    fun reportPresence(link: LinkedEasyPi, addresses: List<String>? = null) {
        request(link, "/api/v1/mobile/prism/presence", "POST", JSONObject().put("addresses", JSONArray(addresses ?: localAddresses())))
    }

    fun commands(link: LinkedEasyPi): List<PrismFlowCommand> {
        val payload = request(link, "/api/v1/mobile/prism/commands", "GET")
        val values = JSONArray(payload)
        return buildList {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(
                    PrismFlowCommand(
                        id = item.getString("id"),
                        clientIp = item.getString("client_ip"),
                        clientPort = item.getInt("client_port"),
                        serverIp = item.getString("server_ip"),
                        serverPort = item.getInt("server_port"),
                        protocol = item.getString("protocol"),
                        expiresAtSeconds = item.getDouble("expires_at"),
                    ),
                )
            }
        }
    }

    fun acknowledge(link: LinkedEasyPi, command: PrismFlowCommand, result: String, packageName: String?) {
        val body = JSONObject().put("command_id", command.id).put("result", result)
        if (!packageName.isNullOrBlank()) body.put("package_name", packageName)
        request(link, "/api/v1/mobile/prism/commands/ack", "POST", body)
    }

    internal fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filter { !it.isLoopbackAddress && !it.isMulticastAddress && !it.isAnyLocalAddress }
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .distinct()
        .take(8)
        .toList()

    private fun request(
        link: LinkedEasyPi,
        path: String,
        method: String,
        body: JSONObject? = null,
    ): String {
        val connection = PinnedHttps.open("${link.serverUrl}$path", link.certificateSha256)
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 1_500
            connection.readTimeout = 2_000
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "EasyPi PRISM returned HTTP ${connection.responseCode}" }
            if (connection.responseCode == 204) "" else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

