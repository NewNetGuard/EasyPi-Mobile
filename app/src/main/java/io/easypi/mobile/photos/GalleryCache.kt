package io.easypi.mobile.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import android.util.Size
import io.easypi.mobile.LinkedEasyPi
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Gallery previews: system thumbnails for media on this phone and EasyPi
 * previews kept in memory and in the app's private cache, so scrolling and
 * reopening the gallery do not download them again. Cleared when the phone
 * is unlinked or linked to another EasyPi.
 */
internal object GalleryCache {
    const val TILE_PIXELS = 360
    const val SCREEN_PIXELS = 2560
    private const val THUMBNAIL_DIR = "gallery-thumbnails"
    private const val VIEWER_DIR = "gallery-viewer"
    private const val MAX_DISK_BYTES = 96L * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    // EasyPi renders missing previews on demand; a few at a time keeps the Pi responsive.
    private val network = Semaphore(3)
    private val diskLock = Any()
    private var writesSinceTrim = 0

    fun cachedLocal(media: LocalMedia): Bitmap? = memory.get(localKey(media))

    fun cachedRemote(linkKey: String, photo: RemotePhoto): Bitmap? = memory.get(remoteKey(linkKey, photo))

    suspend fun localThumbnail(context: Context, media: LocalMedia): Bitmap {
        val key = localKey(media)
        memory.get(key)?.let { return it }
        return runInterruptible(Dispatchers.IO) {
            context.contentResolver.loadThumbnail(media.uri, Size(TILE_PIXELS, TILE_PIXELS), null)
        }.also { memory.put(key, it) }
    }

    suspend fun remoteThumbnail(context: Context, link: LinkedEasyPi, linkKey: String, photo: RemotePhoto): Bitmap {
        val key = remoteKey(linkKey, photo)
        memory.get(key)?.let { return it }
        val file = File(directory(context, THUMBNAIL_DIR), key.removePrefix("r:") + ".jpg")
        val stored = runInterruptible(Dispatchers.IO) {
            if (file.isFile) {
                runCatching { decodeScaled(ImageDecoder.createSource(file), TILE_PIXELS) }
                    .onSuccess { file.setLastModified(System.currentTimeMillis()) }
                    .getOrNull()
            } else {
                null
            }
        }
        val bitmap = stored ?: network.withPermit {
            runInterruptible(Dispatchers.IO) {
                val bytes = CloudMediaClient.thumbnail(link, photo)
                val decoded = decodeScaled(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), TILE_PIXELS)
                store(context, file, bytes)
                decoded
            }
        }
        memory.put(key, bitmap)
        return bitmap
    }

    /** Where the viewer keeps an original downloaded from EasyPi until the viewer closes. */
    fun viewerFile(context: Context, linkKey: String, photo: RemotePhoto): File {
        val extension = photo.filename.substringAfterLast('.', "").filter(Char::isLetterOrDigit).take(8)
        return File(directory(context, VIEWER_DIR), sha256("$linkKey\n${photo.id}") + if (extension.isEmpty()) "" else ".$extension")
    }

    fun clearViewer(context: Context) {
        File(context.cacheDir, VIEWER_DIR).deleteRecursively()
    }

    fun clear(context: Context) {
        memory.evictAll()
        synchronized(diskLock) { File(context.cacheDir, THUMBNAIL_DIR).deleteRecursively() }
        clearViewer(context)
    }

    fun decodeScaled(source: ImageDecoder.Source, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longest / sample > maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
        }

    private fun store(context: Context, file: File, bytes: ByteArray) = synchronized(diskLock) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        runCatching {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.delete()
        }.onFailure { temporary.delete() }
        if (++writesSinceTrim >= 32) {
            writesSinceTrim = 0
            trim(directory(context, THUMBNAIL_DIR))
        }
    }

    private fun trim(directory: File) {
        val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            total -= file.length()
            file.delete()
        }
    }

    private fun directory(context: Context, name: String): File =
        File(context.cacheDir, name).apply { mkdirs() }

    private fun localKey(media: LocalMedia) = "l:${media.uri}:${media.key}"

    private fun remoteKey(linkKey: String, photo: RemotePhoto) =
        "r:" + sha256("$linkKey\n${photo.id}\n${photo.sizeBytes}\n${photo.capturedAt}")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
