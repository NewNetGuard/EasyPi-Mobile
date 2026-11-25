package io.easypi.mobile.sharing

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Process
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.widget.Toast
import io.easypi.mobile.EasyPiLinkStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShareToEasyPiActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val linkedEasyPi = EasyPiLinkStore(this).linkedEasyPi()
        if (linkedEasyPi == null) {
            Toast.makeText(this, "Link this phone to EasyPi before sending files", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uris = sharedUris(intent)
        if (uris.isEmpty() || uris.size > UploadQueue.MAX_FILES || uris.any { !isGrantedExternalContent(it) }) {
            Toast.makeText(this, "Share up to 16 files using a temporary content read grant", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Send files to EasyPi?")
            .setMessage("Send ${uris.size} file(s) to ${linkedEasyPi.displayName}? Each file must be under 256 MiB.")
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .setPositiveButton("Send") { _, _ ->
                if (!copying.compareAndSet(false, true)) {
                    Toast.makeText(this, "Another share is already being processed", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                copyExecutor.execute {
            val result = runCatching {
                val directory = UploadQueue.directory(filesDir, linkedEasyPi)
                uris.forEach { copyToPrivateQueue(it, directory) }
                // A link changed while copying must not transfer queued data.
                check(EasyPiLinkStore(this).linkedEasyPi() == linkedEasyPi) { "Connection changed" }
                EasyPiUploader.uploadPending(linkedEasyPi, directory)
            }
            copying.set(false)
            runOnUiThread {
                val upload = result.getOrNull()
                Toast.makeText(
                    this,
                    when {
                        result.isFailure -> "Could not queue the shared file"
                        upload != null && upload.failed == 0 -> "Sent ${upload.uploaded} file(s) to ${linkedEasyPi.displayName}"
                        else -> "Queued safely; EasyPi is currently unavailable"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
                }
            }.show()
    }

    private fun isGrantedExternalContent(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        if (uri.authority == packageName || uri.authority!!.startsWith("$packageName.")) return false
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return false
        val clip = intent.clipData ?: return false
        if ((0 until clip.itemCount).none { clip.getItemAt(it).uri == uri }) return false
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private fun sharedUris(source: Intent): List<Uri> = when (source.action) {
        Intent.ACTION_SEND -> listOfNotNull(source.uriExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> source.uriListExtra(Intent.EXTRA_STREAM)
        else -> emptyList()
    }

    private fun copyToPrivateQueue(uri: Uri, directory: File) {
        check(directory.exists() || directory.mkdirs())
        val queued = directory.listFiles().orEmpty().filter { it.isFile }
        check(queued.size < UploadQueue.MAX_FILES) { "Upload queue is full" }
        val totalQueued = directory.parentFile!!.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val budget = minOf(UploadQueue.MAX_FILE_BYTES, UploadQueue.MAX_QUEUE_BYTES - totalQueued)
        check(budget > 0 && directory.usableSpace > UploadQueue.MIN_FREE_BYTES) { "Upload queue is full" }
        val partial = File(directory, "${java.util.UUID.randomUUID()}.partial")
        val cancellation = CancellationSignal()
        val descriptor = java.util.concurrent.atomic.AtomicReference<android.content.res.AssetFileDescriptor?>()
        val timeout = timeoutExecutor.schedule({
            cancellation.cancel()
            runCatching { descriptor.get()?.close() }
        }, UploadQueue.COPY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        try {
            val name = safeName(displayName(uri, cancellation))
            val destination = uniqueFile(directory, name)
            contentResolver.openAssetFileDescriptor(uri, "r", cancellation).use { asset ->
                requireNotNull(asset) { "Shared content is unavailable" }
                descriptor.set(asset)
                if (cancellation.isCanceled) error("Shared file took too long to read")
                asset.createInputStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        UploadQueue.copyBounded(input, output, budget) { directory.usableSpace }
                    }
                }
            }
            check(partial.renameTo(destination)) { "Could not finish queued file" }
        } finally {
            timeout.cancel(false)
            partial.delete()
        }
    }

    private fun displayName(uri: Uri, cancellation: CancellationSignal): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) return cursor.getString(column) ?: "shared-file"
        }
        return uri.lastPathSegment ?: "shared-file"
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
        .take(180)
        .ifBlank { "shared-${System.currentTimeMillis()}" }

    private fun uniqueFile(directory: File, name: String): File {
        var result = File(directory, name)
        var index = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        while (result.exists()) result = File(directory, "$stem (${index++})$extension")
        return result
    }

    @Suppress("DEPRECATION")
    private fun Intent.uriExtra(key: String): Uri? = getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.uriListExtra(key: String): List<Uri> =
        getParcelableArrayListExtra<Uri>(key)?.toList().orEmpty()

    companion object {
        private val copying = AtomicBoolean(false)
        private val copyExecutor = Executors.newSingleThreadExecutor()
        private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    }
}

