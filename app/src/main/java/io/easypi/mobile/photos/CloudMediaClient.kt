package io.easypi.mobile.photos

import android.net.Uri
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.pairing.PinnedHttps
import java.io.File
import java.io.OutputStream
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

data class RemotePhoto(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val capturedAt: String?,
)

object CloudMediaClient {
    private const val API_PREFIX = "/api/v1/mobile/cloud"
    private const val PAGE_SIZE = 500
    private const val MAX_THUMBNAIL_BYTES = 12 * 1024 * 1024

    fun listAll(link: LinkedEasyPi): List<RemotePhoto> =
        (listCategory(link, "photos") + listCategory(link, "videos"))
            .sortedByDescending { it.capturedAt.orEmpty() }

    fun thumbnail(link: LinkedEasyPi, photo: RemotePhoto): ByteArray {
        val connection = connection(link, "$API_PREFIX/thumbnail/${encodePath(photo.id)}")
        return try {
            checkSuccess(connection)
            val declared = connection.contentLengthLong
            check(declared < 0 || declared <= MAX_THUMBNAIL_BYTES) { "Cloud preview is too large" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_THUMBNAIL_BYTES) { "Cloud preview is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Download the original into [target]; a cancelled or failed download leaves no file behind. */
    fun downloadOriginal(
        link: LinkedEasyPi,
        photo: RemotePhoto,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val partial = File(target.parentFile, target.name + ".partial")
        try {
            partial.outputStream().buffered().use { output -> copyOriginal(link, photo, output, onProgress) }
            check(partial.renameTo(target)) { "Could not store the downloaded file" }
        } finally {
            partial.delete()
        }
    }

    /** Stream the original straight into [output], for example a new gallery entry. */
    fun copyOriginal(
        link: LinkedEasyPi,
        photo: RemotePhoto,
        output: OutputStream,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val connection = connection(link, "$API_PREFIX/file/${encodePath(photo.id)}")
        try {
            checkSuccess(connection)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: photo.sizeBytes
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(copied, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun delete(link: LinkedEasyPi, fileId: String) {
        val connection = connection(link, "$API_PREFIX/file/${encodePath(fileId)}")
        try {
            connection.requestMethod = "DELETE"
            checkSuccess(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun listCategory(link: LinkedEasyPi, category: String): List<RemotePhoto> = buildList {
        var offset = 0
        while (true) {
            val connection = connection(link, "$API_PREFIX/files?category=$category&offset=$offset&limit=$PAGE_SIZE")
            val page = try {
                connection.requestMethod = "GET"
                checkSuccess(connection)
                JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } finally {
                connection.disconnect()
            }
            val values = page.getJSONArray("items")
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(RemotePhoto(
                    id = item.getString("id"),
                    filename = item.getString("name"),
                    mimeType = item.optString("mime", "application/octet-stream"),
                    sizeBytes = item.getLong("size"),
                    capturedAt = item.optString("modified").takeIf { it.isNotBlank() && it != "null" },
                ))
            }
            if (!page.optBoolean("has_more", false) || values.length() == 0) break
            offset += values.length()
        }
    }

    private fun connection(link: LinkedEasyPi, path: String) =
        PinnedHttps.open("${link.serverUrl}$path", link.certificateSha256).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
        }

    private fun checkSuccess(connection: HttpsURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
        error(detail?.takeIf { it.isNotBlank() } ?: "EasyPi Cloud returned HTTP $code")
    }

    private fun encodePath(value: String): String = Uri.encode(value, "/")
}
