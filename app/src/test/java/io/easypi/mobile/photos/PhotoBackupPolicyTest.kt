package io.easypi.mobile.photos

import android.net.Uri
import io.easypi.mobile.sharing.UploadHttpException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class PhotoBackupPolicyTest {
    @Test
    fun `videos require explicit opt in`() {
        assertTrue(shouldBackUpMedia("image/jpeg", backupVideos = false))
        assertFalse(shouldBackUpMedia("video/mp4", backupVideos = false))
        assertTrue(shouldBackUpMedia("video/mp4", backupVideos = true))
        assertFalse(shouldBackUpMedia("application/pdf", backupVideos = true))
    }

    @Test
    fun `photos and videos accept only their separate destinations`() {
        assertTrue(isExpectedBackupLocation("Sergey/photo.jpg", "Sergey", "image/jpeg"))
        assertFalse(isExpectedBackupLocation("Sergey/Videos/photo.jpg", "Sergey", "image/jpeg"))

        assertTrue(isExpectedBackupLocation("Sergey/Videos/clip.mp4", "Sergey", "video/mp4"))
        assertFalse(isExpectedBackupLocation("Sergey/clip.mp4", "Sergey", "video/mp4"))
    }

    @Test
    fun `existing server copy is shown as backed up when local state is missing`() {
        val media = local("local-1", "photo.jpg", 1234)
        val remote = remote("Sergey/photo.jpg", "photo.jpg", 1234)

        val match = matchLocalMediaToBackups(listOf(media), listOf(remote), "Sergey", emptyMap()).single()

        assertTrue(match.isBackedUp)
        assertSame(remote, match.remote)
    }

    @Test
    fun `saved server id identifies an already backed up file`() {
        val media = local("local-1", "renamed-locally.jpg", 1234)
        val remote = remote("Sergey/original-name.jpg", "original-name.jpg", 1234)

        val match = matchLocalMediaToBackups(
            listOf(media),
            listOf(remote),
            "Sergey",
            mapOf(media.key to remote.id),
        ).single()

        assertTrue(match.isBackedUp)
        assertSame(remote, match.remote)
    }

    @Test
    fun `one server file is not reused for duplicate local files`() {
        val first = local("local-1", "photo.jpg", 1234)
        val second = local("local-2", "photo.jpg", 1234)
        val remote = remote("Sergey/photo.jpg", "photo.jpg", 1234)

        val matches = matchLocalMediaToBackups(listOf(first, second), listOf(remote), "Sergey", emptyMap())

        assertTrue(matches.first().isBackedUp)
        assertFalse(matches.last().isBackedUp)
        assertNull(matches.last().remote)
    }

    @Test
    fun `the same file in two phone folders is backed up by the one copy EasyPi confirmed`() {
        // EasyPi deduplicates identical content and returns the existing id
        // for the second upload; that file must not wait for upload forever.
        val first = local("camera", "photo.jpg", 1234)
        val second = local("album-copy", "photo.jpg", 1234)
        val remote = remote("Sergey/photo.jpg", "photo.jpg", 1234)

        val matches = matchLocalMediaToBackups(
            listOf(first, second),
            listOf(remote),
            "Sergey",
            mapOf(first.key to remote.id, second.key to remote.id),
        )

        assertTrue(matches.all { it.isBackedUp })
        assertTrue(matches.all { it.remote === remote })
    }

    @Test
    fun `a confirmed duplicate does not depend on which folder is listed first`() {
        val first = local("camera", "photo.jpg", 1234)
        val second = local("album-copy", "photo.jpg", 1234)
        val remote = remote("Sergey/photo.jpg", "photo.jpg", 1234)

        val matches = matchLocalMediaToBackups(
            listOf(first, second),
            listOf(remote),
            "Sergey",
            mapOf(second.key to remote.id),
        )

        assertTrue(matches.all { it.isBackedUp })
    }

    @Test
    fun `removed and refused files are not queued for upload again`() {
        val excluded = local("removed", "a.jpg", 1)
        val refused = local("refused", "b.jpg", 2)
        val fresh = local("fresh", "c.jpg", 3)

        val states = matchLocalMediaToBackups(
            listOf(excluded, refused, fresh),
            emptyList(),
            "Sergey",
            emptyMap(),
            excluded = setOf("removed"),
            failures = mapOf("refused" to "Uploaded file exceeds the 250 MiB photo limit"),
        ).associate { it.media.key to it }

        assertEquals(BackupState.EXCLUDED, states.getValue("removed").state)
        assertEquals(BackupState.FAILED, states.getValue("refused").state)
        assertEquals("Uploaded file exceeds the 250 MiB photo limit", states.getValue("refused").failure)
        assertEquals(BackupState.WAITING, states.getValue("fresh").state)
    }

    @Test
    fun `a copy on EasyPi wins over an old exclusion`() {
        val media = local("restored", "photo.jpg", 1234)
        val remote = remote("Sergey/photo.jpg", "photo.jpg", 1234)

        val match = matchLocalMediaToBackups(
            listOf(media), listOf(remote), "Sergey", emptyMap(), excluded = setOf("restored"),
        ).single()

        assertEquals(BackupState.BACKED_UP, match.state)
    }

    @Test
    fun `only file-specific refusals skip a file`() {
        assertEquals(UploadFailure.SKIP_FILE, classifyUploadFailure(UploadHttpException(413, "too large")))
        assertEquals(UploadFailure.SKIP_FILE, classifyUploadFailure(UploadHttpException(400, "bad flag")))
        assertEquals(UploadFailure.SKIP_FILE, classifyUploadFailure(java.io.FileNotFoundException("gone")))
        assertEquals(UploadFailure.SKIP_FILE, classifyUploadFailure(IllegalArgumentException("File is unavailable")))
        assertEquals(UploadFailure.RETRY_LATER, classifyUploadFailure(UploadHttpException(503, "busy")))
        assertEquals(UploadFailure.RETRY_LATER, classifyUploadFailure(java.net.SocketTimeoutException()))
        assertEquals(UploadFailure.STOP, classifyUploadFailure(UploadHttpException(401, "revoked")))
    }

    @Test
    fun `gallery is grouped by the day media was taken`() {
        val zone = ZoneId.of("Europe/Berlin")
        val today = LocalDate.of(2026, 11, 16)
        fun at(day: LocalDate) = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val items = listOf(
            GalleryItem("a", at(today)),
            GalleryItem("b", at(today)),
            GalleryItem("c", at(today.minusDays(1))),
            GalleryItem("d", at(LocalDate.of(2026, 3, 2))),
            GalleryItem("e", at(LocalDate.of(2025, 7, 9))),
            GalleryItem("f", 0),
        )

        val sections = gallerySections(items, today, zone, Locale.ENGLISH)

        assertEquals(
            listOf("Today", "Yesterday", "2 March", "9 July 2025", "Date unknown"),
            sections.map { it.title },
        )
        assertEquals(listOf("a", "b"), sections.first().items.map { it.key })
    }

    @Test
    fun `the backup bar is shown at once and only claims what is known`() {
        fun status(
            syncing: Boolean = false,
            reportedCompleted: Int = 0,
            reportedTotal: Int = 0,
            remoteKnown: Boolean = true,
            unreachable: Boolean = false,
            backedUp: Int = 0,
            total: Int = 0,
        ) = backupStatus(syncing, reportedCompleted, reportedTotal, remoteKnown, unreachable, backedUp, total)

        // Opening the gallery: EasyPi's list has not arrived yet.
        assertTrue(status(remoteKnown = false, total = 94).indeterminate)
        assertTrue(status(syncing = true).indeterminate)

        val uploading = status(syncing = true, reportedCompleted = 87, reportedTotal = 94)
        assertEquals("Backing up · 87 of 94 files", uploading.title)
        assertEquals(87, uploading.completed)
        assertFalse(uploading.done)

        assertTrue(status(syncing = true, reportedCompleted = 94, reportedTotal = 94).done)
        assertEquals("All 94 files are backed up", status(backedUp = 94, total = 94).title)
        assertEquals("86 of 94 files backed up · 8 waiting", status(backedUp = 86, total = 94).title)
        assertTrue(status(unreachable = true, backedUp = 86, total = 94).paused)
    }

    private fun local(key: String, name: String, size: Long) = LocalMedia(
        uri = mock<Uri>(),
        key = key,
        name = name,
        mimeType = "image/jpeg",
        size = size,
        capturedAtMillis = 1,
    )

    private fun remote(id: String, name: String, size: Long) = RemotePhoto(
        id = id,
        filename = name,
        mimeType = "image/jpeg",
        sizeBytes = size,
        capturedAt = null,
    )
}
