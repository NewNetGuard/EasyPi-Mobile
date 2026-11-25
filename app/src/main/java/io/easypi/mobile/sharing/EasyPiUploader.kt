package io.easypi.mobile.sharing

import android.content.ContentResolver
import android.net.Uri
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.pairing.PinnedHttps
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import org.json.JSONObject

object EasyPiUploader {
    fun uploadPending(link: LinkedEasyPi, directory: File): UploadResult {
        var uploaded = 0
        var failed = 0
        directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".partial") }?.sortedBy { it.lastModified() }?.forEach { file ->
            if (runCatching { upload(link, file) }.isSuccess) {
                if (file.delete()) uploaded++ else failed++
            } else {
                failed++
            }
        }
        return UploadResult(uploaded, failed)
    }

    fun upload(
        link: LinkedEasyPi,
        resolver: ContentResolver,
        uri: Uri,
        filename: String,
        mimeType: String,
        photoBackup: Boolean = false,
        videoBackup: Boolean = false,
        capturedAtMillis: Long? = null,
    ): UploadedCloudFile = upload(
        link,
        filename,
        mimeType,
        photoBackup,
        videoBackup,
        capturedAtMillis,
    ) {
        requireNotNull(resolver.openInputStream(uri)) { "File is unavailable" }
    }

    private fun upload(link: LinkedEasyPi, file: File): UploadedCloudFile =
        upload(link, file.name, "application/octet-stream") { file.inputStream() }

    private fun upload(
        link: LinkedEasyPi,
        filename: String,
        mimeType: String,
        photoBackup: Boolean = false,
        videoBackup: Boolean = false,
        capturedAtMillis: Long? = null,
        openInput: () -> InputStream,
    ): UploadedCloudFile {
        require(!(photoBackup && videoBackup)) { "A media upload cannot be both a photo and a video" }
        val boundary = "EasyPi-${UUID.randomUUID()}"
        val connection = PinnedHttps.open(
            "${link.serverUrl}/api/v1/mobile/upload",
            link.certificateSha256,
        )
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setChunkedStreamingMode(1024 * 1024)
            connection.setRequestProperty("Authorization", "Bearer ${link.mobileToken}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val safeName = filename.replace(Regex("[\r\n\"]"), "_")
            BufferedOutputStream(connection.outputStream, 1024 * 1024).use { output ->
                if (photoBackup) {
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"photo_backup\"\r\n\r\n".toByteArray())
                    output.write("true\r\n".toByteArray())
                }
                if (videoBackup) {
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"video_backup\"\r\n\r\n".toByteArray())
                    output.write("true\r\n".toByteArray())
                }
                capturedAtMillis?.takeIf { it > 0 }?.let { capturedAt ->
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"captured_at_ms\"\r\n\r\n".toByteArray())
                    output.write("$capturedAt\r\n".toByteArray())
                }
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray())
                output.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
                openInput().buffered(1024 * 1024).use { input -> input.copyTo(output, 1024 * 1024) }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw UploadHttpException(code, detail?.takeIf { it.isNotBlank() } ?: "Upload failed with HTTP $code")
            }
            val uploaded = JSONObject(body).getJSONArray("uploaded").getJSONObject(0)
            return UploadedCloudFile(uploaded.getString("id"), uploaded.getString("name"))
        } finally {
            connection.disconnect()
        }
    }
}

/** EasyPi answered, but refused the upload; [code] tells callers whether retrying can help. */
class UploadHttpException(val code: Int, message: String) : IllegalStateException(message)

data class UploadResult(val uploaded: Int, val failed: Int)
data class UploadedCloudFile(val id: String, val name: String)
