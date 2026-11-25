package io.easypi.mobile.cameras

import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.pairing.PinnedHttps
import java.io.ByteArrayOutputStream
import org.json.JSONObject

data class EasyPiCamera(
    val id: String,
    val name: String,
    val location: String?,
    val status: String,
)

object CameraClient {
    private const val API_PREFIX = "/api/v1/mobile/cameras"
    private const val MAXIMUM_FRAME_BYTES = 12 * 1024 * 1024

    fun list(link: LinkedEasyPi): List<EasyPiCamera> {
        val connection = connection(link, API_PREFIX)
        return try {
            val body = readText(connection)
            val values = org.json.JSONArray(body)
            buildList {
                for (index in 0 until values.length()) {
                    val item = values.getJSONObject(index)
                    add(EasyPiCamera(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        location = item.optString("location").takeIf { it.isNotBlank() && it != "null" },
                        status = item.optString("status", "offline"),
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun snapshot(link: LinkedEasyPi, cameraId: String): ByteArray {
        val safeId = android.net.Uri.encode(cameraId)
        val connection = connection(link, "$API_PREFIX/$safeId/snapshot")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw responseError(connection, code)
            val declared = connection.contentLengthLong
            check(declared < 0 || declared <= MAXIMUM_FRAME_BYTES) { "Camera frame is too large" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAXIMUM_FRAME_BYTES) { "Camera frame is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(link: LinkedEasyPi, path: String) =
        PinnedHttps.open("${link.serverUrl}$path", link.certificateSha256).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json, image/jpeg")
            setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
        }

    private fun readText(connection: javax.net.ssl.HttpsURLConnection): String {
        val code = connection.responseCode
        if (code !in 200..299) throw responseError(connection, code)
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun responseError(connection: javax.net.ssl.HttpsURLConnection, code: Int): IllegalStateException {
        val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
        return IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "EasyPi Cameras returned HTTP $code")
    }
}
