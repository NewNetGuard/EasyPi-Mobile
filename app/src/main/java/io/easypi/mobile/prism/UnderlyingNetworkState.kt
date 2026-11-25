package io.easypi.mobile.prism

/** Tracks the physical default network beneath the VPN and rejects stale callbacks. */
internal class UnderlyingNetworkState {
    private var currentNetworkHandle: Long? = null
    private var currentFingerprint: String? = null

    @Synchronized
    fun seed(networkHandle: Long?, fingerprint: String?) {
        currentNetworkHandle = networkHandle
        currentFingerprint = fingerprint
    }

    @Synchronized
    fun update(networkHandle: Long?, fingerprint: String?): Boolean {
        if (currentNetworkHandle == networkHandle && currentFingerprint == fingerprint) return false
        currentNetworkHandle = networkHandle
        currentFingerprint = fingerprint
        return true
    }
}
