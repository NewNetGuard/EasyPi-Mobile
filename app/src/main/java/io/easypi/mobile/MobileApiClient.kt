package io.easypi.mobile

import io.easypi.mobile.pairing.PinnedHttps
import io.easypi.mobile.vpn.HomeTunnelIdentity
import org.json.JSONObject

object MobileApiClient {
    data class Ping(
        val deviceId: String,
        val deviceName: String,
        val serverTime: String,
    )

    data class ChameleonEnrollment(
        val address: String,
        val serverAddress: String,
        val serverDialAddress: String,
        val serverDialAddress6: String,
        val pskKey: String,
        val transportKey: String,
        val realityServerPublicKey: String,
        val dohDomain: String,
        val bufferSize: Int,
        val reconnectInterval: Int,
        val mtu: Int,
    )

    data class HomeTunnelEnrollment(
        val address: String,
        val state: String,
        val credentialEpoch: Int,
        val serverAddress: String,
        val serverDialAddress: String,
        val pskKey: String,
        val transportKey: String,
        val realityServerPublicKey: String,
        val certificateSha256: String,
        val bufferSize: Int,
        val reconnectInterval: Int,
        val mtu: Int,
        val prismLinkRelayAddress: String,
        val prismLinkRelayInstallationId: String,
        val prismLinkRelayRouteKey: String,
        val prismLinkDirectAddress: String,
    )

    data class PhotoFolderSettings(
        val photoFolder: String,
        val backupVideos: Boolean,
    )

    fun enrollHomeTunnel(link: LinkedEasyPi): HomeTunnelEnrollment {
        val identity = HomeTunnelIdentity.proof(ping(link).deviceId)
        val connection = PinnedHttps.open(
            "${link.serverUrl}/api/v1/mobile/home-tunnel/enroll",
            link.certificateSha256,
        )
        try {
            val payload = JSONObject()
                .put("public_key", identity.publicKey)
                .put("signature", identity.signature)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "Home Prism enrollment is unavailable")
            }
            val json = JSONObject(body)
            return HomeTunnelEnrollment(
                address = json.getString("address"),
                state = json.getString("state"),
                credentialEpoch = json.getInt("credential_epoch"),
                serverAddress = json.getString("server_address"),
                serverDialAddress = json.getString("server_dial_address"),
                pskKey = json.getString("psk_key"),
                transportKey = json.getString("transport_key"),
                realityServerPublicKey = json.getString("reality_server_public_key"),
                certificateSha256 = json.getString("certificate_sha256"),
                bufferSize = json.optInt("buffer_size", 1000),
                reconnectInterval = json.optInt("reconnect_interval", 5),
                mtu = json.optInt("mtu", 1200),
                prismLinkRelayAddress = json.getString("prism_link_relay_address"),
                prismLinkRelayInstallationId = json.getString("prism_link_relay_installation_id"),
                prismLinkRelayRouteKey = json.getString("prism_link_relay_route_key"),
                prismLinkDirectAddress = json.optString("prism_link_direct_address"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun enrollChameleon(link: LinkedEasyPi): ChameleonEnrollment {
        val connection = PinnedHttps.open(
            "${link.serverUrl}/api/v1/mobile/chameleon/enroll",
            link.certificateSha256,
        )
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "Chameleon enrollment is unavailable")
            }
            val json = JSONObject(body)
            return ChameleonEnrollment(
                address = json.getString("address"),
                serverAddress = json.getString("server_address"),
                serverDialAddress = json.optString("server_dial_address"),
                serverDialAddress6 = json.optString("server_dial_address6"),
                pskKey = json.getString("psk_key"),
                transportKey = json.getString("transport_key"),
                realityServerPublicKey = json.getString("reality_server_public_key"),
                dohDomain = json.optString("doh_domain"),
                bufferSize = json.optInt("buffer_size", 1000),
                reconnectInterval = json.optInt("reconnect_interval", 5),
                mtu = json.optInt("mtu", 1420),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun setPhotoFolder(link: LinkedEasyPi, folderName: String, backupVideos: Boolean): PhotoFolderSettings {
        val connection = PinnedHttps.open(
            "${link.serverUrl}/api/v1/mobile/config/photo-folder",
            link.certificateSha256,
        )
        try {
            val payload = JSONObject()
                .put("photo_folder", folderName.trim())
                .put("backup_videos", backupVideos)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            connection.outputStream.use { it.write(payload) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "Could not create the photo folder")
            }
            val response = JSONObject(body)
            return PhotoFolderSettings(
                photoFolder = response.getString("photo_folder"),
                backupVideos = response.optBoolean("backup_videos", false),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun ping(link: LinkedEasyPi): Ping {
        val connection = PinnedHttps.open(
            "${link.serverUrl}/api/v1/mobile/ping",
            link.certificateSha256,
        )
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "EasyPi Mobile API is unavailable")
            }
            val json = JSONObject(body)
            check(json.getString("status") == "ok") { "EasyPi returned an invalid Mobile Link response" }
            return Ping(
                deviceId = json.getString("device_id"),
                deviceName = json.getString("device_name"),
                serverTime = json.getString("server_time"),
            )
        } finally {
            connection.disconnect()
        }
    }

}
