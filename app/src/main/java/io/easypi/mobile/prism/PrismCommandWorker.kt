package io.easypi.mobile.prism

import android.content.Context
import android.net.ConnectivityManager
import android.system.OsConstants
import android.util.Log
import io.easypi.mobile.BuildConfig
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.LinkedEasyPi
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class PrismCommandWorker(
    private val context: Context,
    private val linkedEasyPi: LinkedEasyPi? = null,
    private val presenceAddresses: List<String>? = null,
    private val installRule: ((PrismFlowCommand, Long) -> Boolean)? = null,
    private val resolveSource: ((PrismFlowCommand) -> InetSocketAddress?)? = null,
) {
    private val active = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!active.compareAndSet(false, true)) return
        thread = Thread(::run, "PrismCommandChannel").apply { start() }
    }

    fun stop() {
        active.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val link = linkedEasyPi ?: EasyPiLinkStore(context).linkedEasyPi() ?: return
        var nextPresence = 0L
        while (active.get()) {
            val now = System.currentTimeMillis()
            if (now >= nextPresence) {
                runCatching { PrismCommandClient.reportPresence(link, presenceAddresses) }
                nextPresence = now + PRESENCE_INTERVAL_MILLIS
            }
            runCatching { PrismCommandClient.commands(link) }.getOrDefault(emptyList()).forEach { command ->
                val owner = ownerPackage(command)
                val remainingMillis = (command.expiresAtSeconds * 1_000.0 - System.currentTimeMillis().toDouble()).toLong()
                    .coerceAtMost(MAX_RULE_TTL_MILLIS)
                val installer = installRule
                val result = when {
                    remainingMillis < MIN_RULE_TTL_MILLIS -> "expired"
                    owner == null -> "not_found"
                    // At home Android proves which package owns the original
                    // app-side tuple, allowing the EasyPi gateway to enforce it.
                    installer == null -> "matched"
                    runCatching { installer.invoke(command, remainingMillis) }.getOrDefault(false) -> "blocked"
                    else -> "unsupported"
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Acknowledging ${command.id} result=$result owner=$owner remainingMs=$remainingMillis")
                }
                runCatching { PrismCommandClient.acknowledge(link, command, result, owner) }
            }
            try {
                Thread.sleep(COMMAND_POLL_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun ownerPackage(command: PrismFlowCommand): String? = runCatching {
        val protocol = when (command.protocol.lowercase()) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp", "quic" -> OsConstants.IPPROTO_UDP
            else -> return null
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val resolver = resolveSource
        val local = if (resolver == null) {
            InetSocketAddress(InetAddress.getByName(command.clientIp), command.clientPort)
        } else {
            resolver.invoke(command) ?: return null
        }
        val uid = connectivity.getConnectionOwnerUid(
            protocol,
            local,
            InetSocketAddress(InetAddress.getByName(command.serverIp), command.serverPort),
        )
        val owner = context.packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: context.packageManager.getNameForUid(uid)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Resolved ${command.protocol} $local -> ${command.serverIp}:${command.serverPort} to uid=$uid owner=$owner")
        }
        owner
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w(TAG, "Could not resolve PRISM flow owner", it)
    }.getOrNull()

    companion object {
        private const val COMMAND_POLL_MILLIS = 750L
        private const val PRESENCE_INTERVAL_MILLIS = 30_000L
        private const val MIN_RULE_TTL_MILLIS = 100L
        private const val MAX_RULE_TTL_MILLIS = 30_000L
        private const val TAG = "EasyPiPrism"
    }
}

