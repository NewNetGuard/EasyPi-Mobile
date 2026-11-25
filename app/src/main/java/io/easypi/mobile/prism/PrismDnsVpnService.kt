package io.easypi.mobile.prism

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import easypinative.Easypinative
import easypinative.PrismBlockObserver
import easypinative.PrismFlowOwnerResolver
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.LinkedEasyPi
import io.easypi.mobile.MainActivity
import io.easypi.mobile.MobileApiClient
import io.easypi.mobile.R
import io.easypi.mobile.VpnRoutingMode
import io.easypi.mobile.dashboard.ProtectionStatsClient
import io.easypi.mobile.dashboard.DnsStatsAccumulator
import io.easypi.mobile.vpn.ChameleonController
import io.easypi.mobile.vpn.HomeNetworkDetector
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PrismTransport { DIRECT, HOME, CHAMELEON }

/**
 * The VPN runs only while Protection or Chameleon is on; the service stops otherwise.
 * Protection filters on the phone and sends traffic straight to the internet
 * (DIRECT); strict Protection carries everything through the home EasyPi (HOME).
 */
internal fun prismTransport(protection: Boolean, chameleon: Boolean, strict: Boolean): PrismTransport {
    require(protection || chameleon) { "No VPN transport is enabled" }
    return when {
        chameleon -> PrismTransport.CHAMELEON
        strict -> PrismTransport.HOME
        else -> PrismTransport.DIRECT
    }
}

/**
 * Whether a start command must forget the switches saved by an earlier service
 * instance. Android restores a VPN it killed with a null intent (or the
 * always-on VPN action); that restore keeps them. An explicit switch action
 * that creates the service means the previous instance ended without the user
 * turning anything off, so only the switch the user just changed may apply.
 */
/**
 * The home tunnel's LAN endpoint, offered only when [verifiedHomeOrigin] (the
 * EasyPi origin that just passed certificate pinning on this network) is the
 * same host. Otherwise the phone uses the relay alone.
 */
internal fun verifiedDirectAddress(directAddress: String, verifiedHomeOrigin: String?): String {
    if (directAddress.isBlank() || verifiedHomeOrigin.isNullOrBlank()) return ""
    val verifiedHost = runCatching { java.net.URI(verifiedHomeOrigin).host }.getOrNull()
        ?.trim('[', ']')?.lowercase() ?: return ""
    val directHost = directAddress.substringBeforeLast(':').trim('[', ']').lowercase()
    return if (directHost.isNotEmpty() && directHost == verifiedHost) directAddress else ""
}

internal fun discardsPreviousRunState(firstCommand: Boolean, action: String?): Boolean =
    firstCommand && action in PrismDnsVpnService.SWITCH_ACTIONS

/** One Android VpnService with independent PRISM and Chameleon switches. */
class PrismDnsVpnService : VpnService() {
    private val active = AtomicBoolean(false)
    private val reconfigureScheduled = AtomicBoolean(false)
    private val reconfigureAgain = AtomicBoolean(false)
    private val recoverCarrier = AtomicBoolean(false)
    private val transportRestartRequested = AtomicBoolean(false)
    private val underlyingNetworkState = UnderlyingNetworkState()
    private val startupExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "EasyPi-VPN-state") }
    private val statisticsExecutor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "EasyPi-PRISM-stats") }
    private val dnsStatistics = DnsStatsAccumulator()
    private lateinit var connectivity: ConnectivityManager
    private var tunnel: ParcelFileDescriptor? = null
    private var httpsPolicyWorker: PrismHttpsPolicyWorker? = null
    @Volatile private var engineTransport: PrismTransport? = null
    private var handledCommand = false
    private var disconnectedChecks = 0
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            reevaluateUnderlyingNetwork()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            reevaluateUnderlyingNetwork()
        }

        override fun onLost(network: Network) {
            reevaluateUnderlyingNetwork()
        }
    }

    private fun reevaluateUnderlyingNetwork() {
        val network = preferredUnderlyingNetwork()
        val changed = underlyingNetworkState.update(
            network?.networkHandle,
            network?.let(connectivity::getLinkProperties)?.physicalFingerprint(),
        )
        if (changed) refreshProtectionRoute(restartTransport = true)
        runCatching {
            statisticsExecutor.schedule({
                val settled = preferredUnderlyingNetwork()
                if (underlyingNetworkState.update(
                        settled?.networkHandle,
                        settled?.let(connectivity::getLinkProperties)?.physicalFingerprint(),
                    )
                ) refreshProtectionRoute(restartTransport = true)
            }, NETWORK_SETTLE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun preferredUnderlyingNetwork(): Network? {
        connectivity.activeNetwork?.takeIf(::isPhysicalNetwork)?.let { return it }
        return connectivity.allNetworks
            .asSequence()
            .filter(::isPhysicalNetwork)
            .sortedWith(compareByDescending<Network> { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }.thenByDescending { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            })
            .firstOrNull()
    }

    private fun isPhysicalNetwork(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.let { capabilities ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true

    // Transports dial their endpoints over IPv4. A newly settling network keeps
    // updating IPv6 addresses, routes and DNS for several seconds; restarting
    // the tunnel for each update tore down every connection repeatedly.
    private fun LinkProperties.physicalFingerprint(): String = buildString {
        append(interfaceName.orEmpty())
        append('|')
        linkAddresses
            .filter { it.address is java.net.Inet4Address }
            .map { it.address.hostAddress.orEmpty() + "/" + it.prefixLength }
            .sorted()
            .joinTo(this, ",")
    }

    private fun refreshProtectionRoute(restartTransport: Boolean = false) {
        val preferences = runtimePreferences()
        // Only the home tunnel is bound to the network it was dialled on; the
        // direct engine's sockets follow Android's default network by themselves.
        if (preferences.getBoolean(KEY_PROTECTION_ENABLED, false) &&
            !preferences.getBoolean(KEY_CHAMELEON_ENABLED, false) &&
            PrismDnsController.isStrict(this)
        ) {
            if (restartTransport) transportRestartRequested.set(true)
            scheduleReconfigure()
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        val initialNetwork = preferredUnderlyingNetwork()
        underlyingNetworkState.seed(
            initialNetwork?.networkHandle,
            initialNetwork?.let(connectivity::getLinkProperties)?.physicalFingerprint(),
        )
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            networkCallback,
        )
        statisticsExecutor.scheduleWithFixedDelay(::flushStatistics, 1, 1, TimeUnit.SECONDS)
        statisticsExecutor.scheduleWithFixedDelay(::checkCarrier, 5, 5, TimeUnit.SECONDS)
    }

    private fun carrierConnected(): Boolean? = runCatching {
        val stats = when (engineTransport) {
            PrismTransport.HOME -> Easypinative.easyPiPrismLinkStats()
            PrismTransport.CHAMELEON -> Easypinative.easyPiChameleonStats()
            else -> return true
        }
        JSONObject(stats).getBoolean("connected")
    }.getOrNull()

    private fun checkCarrier() {
        // The home transport reconnects inside the native engine. Rebuilding
        // the whole VPN on top of that aborted its handshakes and dropped every
        // connection each 15 s while the network was unavailable.
        if (startupInProgress.get() || shutdownInProgress.get() || !active.get() ||
            engineTransport != PrismTransport.CHAMELEON ||
            carrierConnected() != false
        ) {
            disconnectedChecks = 0
            return
        }
        if (++disconnectedChecks < 3) return
        disconnectedChecks = 0
        recoverCarrier.set(true)
        scheduleReconfigure()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preferences = runtimePreferences()
        if (discardsPreviousRunState(firstCommand = !handledCommand, action = intent?.action)) {
            // The switches persist only so that Android can restore a VPN it
            // killed. A user action that starts a new service instance means
            // the previous one is gone (app update, force stop): its switches
            // no longer describe a running tunnel and must not come back on.
            preferences.edit().clear().apply()
        }
        handledCommand = true
        when (intent?.action) {
            ACTION_ENABLE_PROTECTION -> preferences.edit().putBoolean(KEY_PROTECTION_ENABLED, true).apply()
            ACTION_DISABLE_PROTECTION -> preferences.edit().putBoolean(KEY_PROTECTION_ENABLED, false).apply()
            ACTION_ENABLE_CHAMELEON -> preferences.edit().putBoolean(KEY_CHAMELEON_ENABLED, true).apply()
            ACTION_RESTART_TRANSPORT -> transportRestartRequested.set(true)
            ACTION_DISABLE_CHAMELEON -> preferences.edit().putBoolean(KEY_CHAMELEON_ENABLED, false).apply()
            ACTION_STOP_ALL -> preferences.edit().clear().apply()
        }
        val protection = preferences.getBoolean(KEY_PROTECTION_ENABLED, false)
        val chameleon = preferences.getBoolean(KEY_CHAMELEON_ENABLED, false)
        if (protection || chameleon) showForegroundNotification(starting = true, protection, chameleon)
        scheduleReconfigure()
        return if (protection || chameleon) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(networkCallback)
        stopTransport()
        startupInProgress.set(false)
        shutdownInProgress.set(false)
        startupExecutor.shutdownNow()
        statisticsExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        runtimePreferences().edit().clear().apply()
        stopTransport()
        stopSelf()
        super.onRevoke()
    }

    private fun scheduleReconfigure() {
        if (!reconfigureScheduled.compareAndSet(false, true)) {
            reconfigureAgain.set(true)
            return
        }
        startupInProgress.set(true)
        lastStartError = null
        startupExecutor.execute {
            do {
                reconfigureAgain.set(false)
                applyDesiredState()
            } while (reconfigureAgain.get())
            shutdownInProgress.set(false)
            startupInProgress.set(false)
            reconfigureScheduled.set(false)
            if (reconfigureAgain.get()) scheduleReconfigure()
        }
    }

    private fun applyDesiredState() {
        val recovering = recoverCarrier.getAndSet(false)
        val restartTransport = transportRestartRequested.getAndSet(false)
        val preferences = runtimePreferences()
        val protection = preferences.getBoolean(KEY_PROTECTION_ENABLED, false)
        val chameleon = preferences.getBoolean(KEY_CHAMELEON_ENABLED, false)
        if (!protection && !chameleon) {
            stopTransport()
            startupInProgress.set(false)
            shutdownInProgress.set(false)
            stopSelf()
            return
        }
        val link = EasyPiLinkStore(this).linkedEasyPi() ?: return failStart("This phone is not linked to EasyPi")
        // A pinned direct API remains the lowest-latency policy path even when
        // Chameleon carries application traffic. Transport choice stays
        // independent: enabling Chameleon must never bypass the VPN.
        val homeGateway = if (protection) {
            HomeNetworkDetector.directEndpoint(this, link, requireIpv4Gateway = true)
        } else null
        val policyLink = homeGateway?.let { link.copy(serverUrl = it) } ?: link
        // Strict Protection is a full home tunnel: EasyPi sees and enforces the
        // phone's traffic and the public address is the home's egress. Normal
        // Protection filters on the phone with EasyPi's complete policy and
        // sends traffic straight out, so its speed does not depend on the
        // relay. The public Chameleon switch remains independent and takes
        // precedence only when the user explicitly enables it.
        val desiredTransport = prismTransport(protection, chameleon, PrismDnsController.isStrict(this))
        // Through the home tunnel NetProtection on the EasyPi enforces every
        // policy, DNS included, so the phone does not filter on its own.
        // Otherwise local PRISM asks EasyPi about every new domain.
        val localPrism = protection && desiredTransport != PrismTransport.HOME
        val result = runCatching {
            if (desiredTransport != PrismTransport.CHAMELEON) {
                // The home-tunnel enrollment is also this phone's policy
                // identity on EasyPi: local PRISM decisions use its profile.
                val store = EasyPiLinkStore(this)
                val refreshed = runCatching { MobileApiClient.enrollHomeTunnel(policyLink) }
                refreshed.onSuccess(store::saveHomeTunnelEnrollment)
                if (desiredTransport == PrismTransport.HOME &&
                    refreshed.isFailure && store.homeTunnelEnrollment() == null
                ) {
                    throw requireNotNull(refreshed.exceptionOrNull())
                }
            }
            val carrierLost = recovering && carrierConnected() == false
            if (!active.get() || engineTransport != desiredTransport || carrierLost || restartTransport) {
                stopTransport(keepTunnel = true)
                Easypinative.setPrismEnabled(localPrism)
                configurePrism(policyLink, localPrism)
                when (desiredTransport) {
                    PrismTransport.DIRECT -> startDirectTransport()
                    PrismTransport.HOME -> startHomeTransport(homeGateway)
                    PrismTransport.CHAMELEON -> startChameleonTransport()
                }
                engineTransport = desiredTransport
                active.set(true)
            }
            else configurePrism(policyLink, localPrism)
            isRunning = true
            isChameleonRunning = chameleon
            isProtectionRunning = protection
            trafficBlocked = false
            if (chameleon && vpnConnectedAtMillis == 0L) vpnConnectedAtMillis = System.currentTimeMillis()
            if (!chameleon) vpnConnectedAtMillis = 0L
            showForegroundNotification(starting = false, protection, chameleon)
        }
        result.onFailure { error ->
            lastStartError = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            Log.e(TAG, "EasyPi VPN state change failed", error)
            stopTransport(keepTunnel = tunnel != null)
            if (trafficBlocked) showForegroundNotification(false, protection, chameleon) else stopSelf()
        }
    }

    private fun startDirectTransport() {
        val descriptor = Builder()
            .setSession("EasyPi Protection")
            .setMtu(DIRECT_MTU)
            .addAddress(DIRECT_ADDRESS_V4, 32)
            .addRoute("0.0.0.0", 0)
            // The direct engine forwards IPv6 itself, through the same filter,
            // so IPv6 stays inside Protection instead of bypassing it.
            .addAddress(DIRECT_ADDRESS_V6, 128)
            .addRoute("::", 0)
            .addDnsServer(PRISM_DNS_V4)
            .addDisallowedApplication(packageName)
            .setBlocking(true)
            .establish()
            ?: error("Android did not establish the Protection tunnel")
        val previous = tunnel
        tunnel = descriptor
        previous?.close()
        // Ownership of the detached descriptor transfers to the native engine,
        // including every failure path.
        Easypinative.startEasyPiProtectionTun(
            ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd().toLong(),
        )
    }

    private fun startChameleonTransport() {
        val store = EasyPiLinkStore(this)
        val profile = store.chameleonProfile() ?: error("Chameleon is not configured for this phone")
        val builder = Builder()
            .setSession("EasyPi Chameleon VPN")
            .setMtu(profile.mtu)
            .addAddress(profile.address.substringBefore('/'), profile.address.substringAfter('/').toInt())
            .addRoute("0.0.0.0", 0)
            // Chameleon carries IPv4 only, and Huawei's VPN stack rejects an
            // IPv6 address on an IPv4-only tunnel. Add no IPv6 address or
            // route: Android then installs an unreachable IPv6 default route
            // for the VPN, so IPv6 fails at once, cannot bypass the VPN, and
            // applications fall back to IPv4. An IPv6 route without an IPv6
            // address is not installed on the TUN at all.
            .addDnsServer(PRISM_DNS_V4)
            .setBlocking(true)
        applyAppRouting(builder, store)
        val descriptor = builder.establish() ?: error("Android did not establish the Chameleon tunnel")
        val previous = tunnel
        tunnel = descriptor
        previous?.close()
        Easypinative.startEasyPiChameleon(
            ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd().toLong(),
            ChameleonController.config(profile),
            filesDir.resolve("chameleon-profile.json").absolutePath,
        )
    }

    private fun startHomeTransport(verifiedHomeOrigin: String?) {
        val enrollment = EasyPiLinkStore(this).homeTunnelEnrollment()
            ?: error("Home tunnel is not configured for this phone")
        val profile = enrollment.prismLink
        val builder = Builder()
            .setSession("EasyPi Home Protection")
            .setMtu(profile.mtu)
            .addAddress(profile.address.substringBefore('/'), profile.address.substringAfter('/').toInt())
            .addRoute("0.0.0.0", 0)
            // The home transport carries IPv4 only, and Huawei's VPN stack
            // rejects an IPv6 address on it. Add no IPv6 address or route:
            // Android then installs an unreachable IPv6 default route for the
            // VPN, so IPv6 fails at once and applications fall back to IPv4
            // through the home EasyPi. An IPv6 route without an IPv6 address
            // is not installed on the TUN, and IPv6 leaked past Protection
            // over the phone's own network.
            // NetProtection on the home EasyPi answers DNS at the tunnel
            // gateway, so per-device policy applies to the phone's queries.
            .addDnsServer(HOME_TUNNEL_DNS_V4)
            .addDisallowedApplication(packageName)
            .setBlocking(true)
        val descriptor = builder.establish() ?: error("Android did not establish the home tunnel")
        val previous = tunnel
        tunnel = descriptor
        previous?.close()
        val config = JSONObject()
            .put("address", profile.address)
            .put("psk_key", profile.pskKey)
            .put("relay_address", profile.relayAddress)
            .put("relay_installation_id", profile.relayInstallationId)
            .put("relay_route_key", profile.relayRouteKey)
            // Only on the home network, where the EasyPi just proved its paired
            // certificate: elsewhere the LAN address may belong to a stranger,
            // who must not receive this phone's tunnel hello.
            .put("direct_address", verifiedDirectAddress(profile.directAddress, verifiedHomeOrigin))
            .put("mtu", profile.mtu)
            .toString()
        Easypinative.startEasyPiPrismLink(
            ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd().toLong(),
            config,
        )
    }

    private fun configurePrism(link: LinkedEasyPi, enabled: Boolean) {
        httpsPolicyWorker?.stop()
        httpsPolicyWorker = null
        Easypinative.clearPrismBlockObserver()
        Easypinative.clearPrismFlowRules()
        Easypinative.setPrismEnabled(enabled)
        // Protection owns DNS, including strict Android Private DNS. Otherwise
        // encrypted/TCP resolvers could bypass the selected domain policy.
        Easypinative.setPrismDNSProxyEnabled(enabled)
        Easypinative.setPrismBlockObserver(blockObserver)
        if (!enabled) {
            Easypinative.clearPrismDomainPolicyResolver()
            Easypinative.clearPrismFlowOwnerResolver()
            return
        }
        Easypinative.setPrismFlowOwnerResolver(flowOwnerResolver)
        policyUnavailable = enabled
        httpsPolicyWorker = PrismHttpsPolicyWorker(link,
            onHealth = { available ->
                policyUnavailable = !available
                if (isProtectionRunning) showForegroundNotification(false, true, isChameleonRunning)
            },
        ).also { worker ->
            worker.start()
            Easypinative.setPrismDomainPolicyResolver(worker)
        }
        // PRISM policy is evaluated on the phone through authenticated HTTPS.
        // The home gateway cannot observe flows that exit at the public
        // Chameleon endpoint, so polling it for exact-flow commands is invalid.
    }

    private fun applyAppRouting(builder: Builder, store: EasyPiLinkStore) {
        if (store.vpnRoutingMode() == VpnRoutingMode.ALL_TRAFFIC) {
            builder.addDisallowedApplication(packageName)
            return
        }
        val selected = store.selectedVpnApps().filter { selectedPackage ->
            selectedPackage != packageName && runCatching {
                packageManager.getApplicationInfo(selectedPackage, 0)
            }.isSuccess
        }
        require(selected.isNotEmpty()) { "Choose at least one application for VPN routing" }
        selected.forEach(builder::addAllowedApplication)
    }

    private val flowOwnerResolver = object : PrismFlowOwnerResolver {
        override fun owner(protocol: String, clientIP: String, clientPort: Long, serverIP: String, serverPort: Long): String =
            runCatching {
                val protocolNumber = when (protocol.lowercase()) {
                    "tcp" -> OsConstants.IPPROTO_TCP
                    "udp" -> OsConstants.IPPROTO_UDP
                    else -> return ""
                }
                if (clientPort !in 1..65535 || serverPort !in 1..65535) return ""
                val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                    protocolNumber,
                    InetSocketAddress(InetAddress.getByName(clientIP), clientPort.toInt()),
                    InetSocketAddress(InetAddress.getByName(serverIP), serverPort.toInt()),
                )
                packageManager.getPackagesForUid(uid)?.firstOrNull() ?: packageManager.getNameForUid(uid) ?: ""
            }.getOrDefault("")
    }

    private fun flushStatistics() {
        val batches = dnsStatistics.drain()
        if (batches.isEmpty()) return
        runCatching { ProtectionStatsClient.recordBatches(applicationContext, batches) }
            .onFailure { error ->
                dnsStatistics.restore(batches)
                Log.e(TAG, "Unable to persist PRISM statistics", error)
            }
    }

    private val blockObserver = object : PrismBlockObserver {
        override fun onQuery(domain: String) = Unit
        override fun onBlocked(domain: String, reason: String) {
            // Log only fixed diagnostic categories, never hostnames or payload.
            when (reason) {
                "tls_invalid_client_hello" -> Log.w(TAG, "PRISM blocked TLS: invalid ClientHello")
                "tls_ech_extension" -> Log.w(TAG, "PRISM blocked TLS: ECH extension; encrypted name or GREASE")
            }
        }

        override fun onDNSResult(domain: String, blocked: Boolean, occurredAtMillis: Long) {
            dnsStatistics.record(domain, blocked, occurredAtMillis)
        }
    }

    private fun stopTransport(keepTunnel: Boolean = false) {
        flushStatistics()
        active.set(false)
        isRunning = false
        isChameleonRunning = false
        isProtectionRunning = false
        vpnConnectedAtMillis = 0L
        httpsPolicyWorker?.stop()
        httpsPolicyWorker = null
        // Release Android's TUN before asking the native engine to stop. A
        // reader blocked in read(2) could otherwise leave the UI in
        // "Stopping protection…" indefinitely.
        val transportToStop = engineTransport
        engineTransport = null
        if (!keepTunnel) {
            runCatching { tunnel?.close() }
            tunnel = null
        }
        trafficBlocked = keepTunnel && tunnel != null
        when (transportToStop) {
            PrismTransport.DIRECT -> runCatching { Easypinative.stopEasyPiProtectionTun() }
            PrismTransport.HOME -> runCatching { Easypinative.stopEasyPiPrismLink() }
            PrismTransport.CHAMELEON -> runCatching { Easypinative.stopEasyPiChameleon() }
            null -> {
                runCatching { Easypinative.stopEasyPiProtectionTun() }
                runCatching { Easypinative.stopEasyPiChameleon() }
                runCatching { Easypinative.stopEasyPiPrismLink() }
            }
        }
        runCatching { Easypinative.setPrismEnabled(false) }
        runCatching { Easypinative.setPrismDNSProxyEnabled(false) }
        runCatching { Easypinative.clearPrismFlowOwnerResolver() }
        runCatching { Easypinative.clearPrismDomainPolicyResolver() }
        runCatching { Easypinative.clearPrismBlockObserver() }
        runCatching { Easypinative.clearPrismFlowRules() }
        if (!trafficBlocked) stopForeground(STOP_FOREGROUND_REMOVE)
        shutdownInProgress.set(false)
    }

    private fun failStart(message: String) {
        lastStartError = message
        stopTransport(keepTunnel = tunnel != null)
        if (trafficBlocked) showForegroundNotification(false, true, false) else stopSelf()
    }

    private fun showForegroundNotification(starting: Boolean, protection: Boolean, chameleon: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EasyPi network", NotificationManager.IMPORTANCE_LOW).apply {
                description = "PRISM protection and Chameleon VPN"
            },
        )
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = when {
            protection && chameleon -> "PRISM + Chameleon VPN"
            protection -> "PRISM Protection"
            else -> "Chameleon VPN"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(when {
                trafficBlocked -> "EasyPi traffic is blocked"
                starting -> "EasyPi $name is starting"
                else -> "EasyPi $name is active"
            })
            .setContentText(when {
                trafficBlocked -> "Reconnect or explicitly stop VPN/protection to resume traffic"
                starting -> "Applying network settings"
                protection && policyUnavailable -> "Policy unavailable or not checked; unverified traffic is blocked"
                else -> "Mobile Link remains independent"
            })
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun runtimePreferences() = getSharedPreferences(RUNTIME_PREFERENCES, MODE_PRIVATE)

    companion object {
        const val ACTION_ENABLE_PROTECTION = "io.easypi.mobile.prism.ENABLE_PROTECTION"
        const val ACTION_DISABLE_PROTECTION = "io.easypi.mobile.prism.DISABLE_PROTECTION"
        const val ACTION_ENABLE_CHAMELEON = "io.easypi.mobile.prism.ENABLE_CHAMELEON"
        const val ACTION_DISABLE_CHAMELEON = "io.easypi.mobile.prism.DISABLE_CHAMELEON"
        const val ACTION_STOP_ALL = "io.easypi.mobile.prism.STOP_ALL"
        const val ACTION_RESTART_TRANSPORT = "io.easypi.mobile.prism.RESTART_TRANSPORT"
        internal val SWITCH_ACTIONS = setOf(
            ACTION_ENABLE_PROTECTION,
            ACTION_DISABLE_PROTECTION,
            ACTION_ENABLE_CHAMELEON,
            ACTION_DISABLE_CHAMELEON,
            ACTION_STOP_ALL,
        )
        private const val PRISM_DNS_V4 = "10.111.0.2"
        private const val DIRECT_MTU = 1500
        private const val DIRECT_ADDRESS_V4 = "10.111.0.1"
        private const val DIRECT_ADDRESS_V6 = "fd00:65:6173:7970::1"
        // Gateway of the EasyPi home tunnel network (10.77.0.0/24).
        private const val HOME_TUNNEL_DNS_V4 = "10.77.0.1"
        private const val CHANNEL_ID = "easypi-network"
        private const val NOTIFICATION_ID = 4103
        private const val TAG = "EasyPiVpn"
        private const val RUNTIME_PREFERENCES = "easypi_vpn_runtime"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
        private const val KEY_CHAMELEON_ENABLED = "chameleon_enabled"
        private const val NETWORK_SETTLE_DELAY_MS = 500L

        @Volatile var isRunning = false
            private set
        @Volatile var isChameleonRunning = false
            private set
        @Volatile var isProtectionRunning = false
            private set
        @Volatile var vpnConnectedAtMillis = 0L
            private set
        val startupInProgress = AtomicBoolean(false)
        val shutdownInProgress = AtomicBoolean(false)
        @Volatile var lastStartError: String? = null
            private set
        @Volatile var policyUnavailable = false
            private set
        @Volatile var trafficBlocked = false
            private set

        fun prepareForStart() {
            lastStartError = null
            startupInProgress.set(true)
        }
    }
}
