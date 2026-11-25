package io.easypi.mobile.prism

import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.pairing.PinnedHttps
import org.json.JSONObject

/** [policyVersion] changes whenever EasyPi's policy changes; empty if unknown. */
data class PrismDecision(val blocked: Boolean, val ttlSeconds: Int, val policyVersion: String = "")

object PrismDecisionClient {
    fun check(link: LinkedEasyPi, domain: String, packageName: String?): PrismDecision {
        val connection = PinnedHttps.open(
            "${link.serverUrl}/api/v1/mobile/prism/dns/check",
            link.certificateSha256,
        )
        var reusable = false
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "keep-alive")
            val body = JSONObject().put("domain", domain)
            if (!packageName.isNullOrBlank()) body.put("package_name", packageName)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            // A fully consumed response can return the pinned TLS connection
            // to Android's keep-alive pool. Failed/incomplete exchanges are
            // explicitly disconnected below and are never reused.
            reusable = true
            check(status == 200) { "EasyPi policy returned HTTP $status" }
            val payload = JSONObject(response)
            PrismDecision(
                blocked = payload.getBoolean("blocked"),
                ttlSeconds = payload.optInt("cache_ttl_seconds", 60).coerceIn(10, 3600),
                policyVersion = payload.optString("policy_version", "").take(64),
            )
        } finally {
            if (!reusable) connection.disconnect()
        }
    }
}
