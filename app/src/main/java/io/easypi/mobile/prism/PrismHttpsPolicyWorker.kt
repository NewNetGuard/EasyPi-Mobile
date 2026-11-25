package io.easypi.mobile.prism

import easypinative.PrismDomainPolicyResolver
import io.easypi.mobile.LinkedEasyPi
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded authoritative policy. Unknown/error decisions never grant access. */
internal class PrismHttpsPolicyWorker(
    private val linkedEasyPi: LinkedEasyPi,
    private val onDecision: (String, Boolean) -> Unit = { _, _ -> },
    private val onHealth: (Boolean) -> Unit = {},
    private val lookup: (LinkedEasyPi, String) -> PrismDecision = { link, domain -> PrismDecisionClient.check(link, domain, null) },
) : PrismDomainPolicyResolver {
    private data class CachedDecision(val blocked: Boolean, val expiresAtMillis: Long)
    private data class PendingDecision(
        val completed: CountDownLatch = CountDownLatch(1),
        @Volatile var blocked: Boolean = true,
    )
    private val running = AtomicBoolean(false)
    private val permits = Semaphore(8)
    private val pending = HashMap<String, PendingDecision>()
    private val cache = object : LinkedHashMap<String, CachedDecision>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDecision>): Boolean = size > 1024
    }

    // Guarded by cache. A different version from EasyPi means its policy
    // changed: every cached decision may be stale and is dropped at once.
    private var policyVersion: String? = null

    fun start() { running.set(true) }
    fun stop() {
        running.set(false)
        synchronized(cache) {
            cache.clear()
            policyVersion = null
        }
        synchronized(pending) {
            pending.values.forEach { it.completed.countDown() }
            pending.clear()
        }
    }
    // Packet-pump callbacks must never perform network I/O. Unknown names are
    // held by the native gate, which schedules a bounded authoritative check.
    override fun shouldBlock(domain: String): Boolean {
        if (!running.get()) return true
        val name = domain.trim().trimEnd('.').lowercase(Locale.US)
        return synchronized(cache) {
            cache[name]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }?.blocked
        } ?: true
    }

    override fun checkBlock(domain: String): Boolean {
        val name = domain.trim().trimEnd('.').lowercase(Locale.US)
        if (name.isBlank() || name.length > 253 || name.any { it.isWhitespace() || it.isISOControl() }) return true
        if (!running.get()) return true
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[name]?.takeIf { it.expiresAtMillis > now } }?.let {
            onDecision(name, it.blocked)
            return it.blocked
        }
        var owner = false
        val request = synchronized(pending) {
            pending[name] ?: PendingDecision().also {
                pending[name] = it
                owner = true
            }
        }
        if (!owner) {
            if (!request.completed.await(1_100, TimeUnit.MILLISECONDS)) {
                onHealth(false)
                return true
            }
            onDecision(name, request.blocked)
            return request.blocked
        }
        var result = true
        if (!permits.tryAcquire()) {
            onHealth(false)
            request.blocked = true
            synchronized(pending) { pending.remove(name, request) }
            request.completed.countDown()
            onDecision(name, true)
            return true
        }
        try {
            result = try {
                val decision = lookup(linkedEasyPi, name)
                if (!running.get()) true else {
                    synchronized(cache) {
                        val version = decision.policyVersion
                        if (version.isNotEmpty() && version != policyVersion) {
                            if (policyVersion != null) cache.clear()
                            policyVersion = version
                        }
                        cache[name] = CachedDecision(decision.blocked, now + decision.ttlSeconds.coerceIn(1, 3600) * 1000L)
                    }
                    onHealth(true)
                    decision.blocked
                }
            } catch (_: Exception) {
                if (running.get()) {
                    synchronized(cache) { cache[name] = CachedDecision(true, now + 2000L) }
                    onHealth(false)
                }
                true
            } finally { permits.release() }
        } finally {
            request.blocked = result
            synchronized(pending) { pending.remove(name, request) }
            request.completed.countDown()
        }
        onDecision(name, result)
        return result
    }
}
