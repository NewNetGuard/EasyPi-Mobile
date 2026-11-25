package io.easypi.mobile.sharing

import io.easypi.mobile.LinkedEasyPi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UploadQueueTest {
    private fun link(token: String, server: String = "https://example.test") =
        LinkedEasyPi(server, null, "Test", token, "a".repeat(64))

    @Test fun replacementPairingCannotAcquireOldQueue() {
        val root = File(System.getProperty("java.io.tmpdir"), "queue-test")
        assertNotEquals(UploadQueue.directory(root, link("a")), UploadQueue.directory(root, link("b")))
        assertNotEquals(UploadQueue.directory(root, link("a")), UploadQueue.directory(root, link("a", "https://other.example")))
        assertNotEquals(File(root, "pending-uploads"), UploadQueue.directory(root, link("a")))
    }

    @Test fun oversizedOrLowSpaceStreamStopsBeforeOverflow() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            UploadQueue.copyBounded(ByteArrayInputStream(ByteArray(32)), output, 16) { Long.MAX_VALUE }
        }
        assertTrue(output.size() <= 16)
        assertThrows(IllegalStateException::class.java) {
            UploadQueue.copyBounded(ByteArrayInputStream(ByteArray(8)), output, 16) { 0 }
        }
    }

    @Test fun retainedFilesNeedExplicitReassignment() {
        val root = java.nio.file.Files.createTempDirectory("easypi-queue-security-").toFile()
        try {
            val old = UploadQueue.directory(root, link("old")).apply { mkdirs() }
            val retained = File(old, "retained.txt").apply { writeText("synthetic") }
            val current = link("new")
            assertFalse(UploadQueue.directory(root, current).exists())
            assertEquals(listOf(retained), UploadQueue.retainedFiles(root, current))
            UploadQueue.reassign(root, current, listOf(retained))
            assertFalse(retained.exists())
            assertTrue(UploadQueue.retainedFiles(root, current).isEmpty())
            assertEquals("synthetic", UploadQueue.directory(root, current).listFiles()!!.single().readText())
        } finally { root.deleteRecursively() }
    }
}
