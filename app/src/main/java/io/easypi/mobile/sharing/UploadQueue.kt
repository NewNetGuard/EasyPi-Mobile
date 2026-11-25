package io.easypi.mobile.sharing

import io.easypi.mobile.LinkedEasyPi
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A replacement pairing never acquires the previous pairing's queued files. */
internal object UploadQueue {
    const val MAX_FILES = 16
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_QUEUE_BYTES = 512L * 1024 * 1024
    const val MIN_FREE_BYTES = 32L * 1024 * 1024
    const val COPY_TIMEOUT_MILLIS = 60_000L

    fun directory(filesDir: File, link: LinkedEasyPi): File {
        val identity = listOf(link.serverUrl, link.certificateSha256.orEmpty(), link.mobileToken).joinToString("\n")
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Old unscoped files are deliberately retained in the parent, never sent.
        return File(File(filesDir, "pending-uploads"), key)
    }

    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long, space: () -> Long) {
        val deadline = System.nanoTime() + COPY_TIMEOUT_MILLIS * 1_000_000
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            check(System.nanoTime() < deadline) { "Shared file took too long to read" }
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            check(copied <= maxBytes && space() >= MIN_FREE_BYTES + count) { "Shared file exceeds available queue space" }
            output.write(buffer, 0, count)
        }
    }

    fun retainedFiles(filesDir: File, link: LinkedEasyPi): List<File> {
        val active = directory(filesDir, link).canonicalFile
        val root = File(filesDir, "pending-uploads")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(2)
            .filter { it.isFile && !it.name.endsWith(".partial") && it.parentFile.canonicalFile != active }
            .take(100).toList()
    }

    /** Called only after the user selects files and confirms the new target. */
    fun reassign(filesDir: File, link: LinkedEasyPi, selected: List<File>) {
        require(selected.isNotEmpty() && selected.size <= MAX_FILES)
        val allowed = retainedFiles(filesDir, link).mapTo(mutableSetOf()) { it.canonicalFile }
        require(selected.all { it.canonicalFile in allowed })
        val target = directory(filesDir, link).apply { check(exists() || mkdirs()) }
        val queued = target.listFiles().orEmpty().filter { it.isFile }
        check(queued.size + selected.size <= MAX_FILES && selected.all { it.length() <= MAX_FILE_BYTES }) { "Queue limit exceeded" }
        check(queued.sumOf { it.length() } + selected.sumOf { it.length() } <= MAX_QUEUE_BYTES) { "Queue limit exceeded" }
        selected.forEach { source ->
            val destination = File(target, java.util.UUID.randomUUID().toString().take(8) + "-" + source.name.take(180))
            check(source.renameTo(destination)) { "Could not move retained file" }
        }
    }
}
