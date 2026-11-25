package io.easypi.mobile.prism

import io.easypi.mobile.LinkedEasyPi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PrismPolicySecurityTest {
    private val link = LinkedEasyPi("https://example.test", null, "Test", "test", "a".repeat(64))

    @Test fun unknownPolicyWaitsAndApiFailureBlocks() {
        val health = mutableListOf<Boolean>()
        val worker = PrismHttpsPolicyWorker(link, onHealth = { health += it }, lookup = { _, _ -> error("unavailable") })
        assertTrue(worker.shouldBlock("example.test"))
        worker.start()
        assertTrue(worker.shouldBlock("example.test"))
        assertTrue(health.isEmpty())
        assertTrue(worker.checkBlock("example.test"))
        assertEquals(listOf(false), health)
        worker.stop()
        assertTrue(worker.checkBlock("other.example"))
    }

    @Test fun cacheIsBoundedAndDoesNotTurnAnErrorIntoAllow() {
        var online = true
        val calls = AtomicInteger()
        val worker = PrismHttpsPolicyWorker(link, lookup = { _, _ ->
            calls.incrementAndGet()
            check(online)
            PrismDecision(false, 60)
        })
        worker.start()
        repeat(1025) { assertFalse(worker.checkBlock("host$it.example")) }
        online = false
        assertTrue(worker.checkBlock("host0.example"))
        assertEquals(1026, calls.get())
        worker.stop()
    }

    @Test fun concurrentPolicyWorkHasAFixedBudget() {
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val worker = PrismHttpsPolicyWorker(link, lookup = { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            PrismDecision(false, 60)
        })
        worker.start()
        try {
            repeat(8) { i -> executor.submit { worker.checkBlock("host$i.example") } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(worker.checkBlock("overflow.example"))
        } finally { release.countDown(); executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS); worker.stop() }
    }

    @Test fun concurrentChecksForOneDomainShareOneAuthoritativeLookup() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        val worker = PrismHttpsPolicyWorker(link, lookup = { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            PrismDecision(false, 60)
        })
        worker.start()
        try {
            val results = (1..8).map { executor.submit<Boolean> { worker.checkBlock("same.example") } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(results.all { !it.get(3, TimeUnit.SECONDS) })
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            worker.stop()
        }
    }

    @Test fun aPolicyChangeOnEasyPiDropsEveryCachedDecision() {
        var version = "v1"
        val blocked = mutableSetOf<String>()
        val calls = AtomicInteger()
        val worker = PrismHttpsPolicyWorker(link, lookup = { _, domain ->
            calls.incrementAndGet()
            PrismDecision(domain in blocked, 60, version)
        })
        worker.start()
        assertFalse(worker.checkBlock("video.example"))
        assertFalse(worker.checkBlock("video.example"))
        assertEquals(1, calls.get())

        // The parent blocks the site; the next lookup of any other name
        // reports the new version and the stale allow is forgotten.
        blocked += "video.example"
        version = "v2"
        assertFalse(worker.checkBlock("news.example"))
        assertTrue(worker.checkBlock("video.example"))
        assertEquals(3, calls.get())

        // Same version: cached decisions stay.
        assertTrue(worker.checkBlock("video.example"))
        assertEquals(3, calls.get())
        worker.stop()
    }
}
