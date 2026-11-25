package io.easypi.mobile.pairing

import io.easypi.mobile.LinkedEasyPi
import org.json.JSONObject
import java.net.URI

object PairingClient {
    data class PairingResult(val link: LinkedEasyPi)

    fun validateServerUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "EasyPi must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "EasyPi address is invalid" }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) { "EasyPi address is invalid" }
        require(uri.path.isNullOrBlank() || uri.path == "/") { "EasyPi address must not contain a path" }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://${uri.host}$port"
    }

    fun complete(
        server: String,
        pairingCode: String,
        deviceName: String,
        certificateSha256: String?,
        homeServer: String? = null,
    ): PairingResult {
        require(pairingCode.length in 32..128) { "Pairing code is invalid" }
        val serverUrl = validateServerUrl(server)
        val homeServerUrl = homeServer?.takeIf { it.isNotBlank() }?.let(::validateServerUrl)
        val certificate = certificateSha256?.let(PinnedHttps::normalizeFingerprint)
        val connection = PinnedHttps.open("$serverUrl/api/v1/mobile/pairing/complete", certificate)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            val body = JSONObject()
                .put("pairing_code", pairingCode)
                .put("device_name", deviceName.take(100))
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "EasyPi rejected the pairing request")
            }
            val json = JSONObject(response)
            return PairingResult(
                link = LinkedEasyPi(
                    serverUrl = serverUrl,
                    homeServerUrl = homeServerUrl,
                    displayName = "Home EasyPi",
                    mobileToken = json.getString("mobile_token"),
                    certificateSha256 = certificate,
                ),
            )
        } finally {
            connection.disconnect()
        }
    }
}
