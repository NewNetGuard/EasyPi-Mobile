package io.easypi.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

enum class VpnRoutingMode { ALL_TRAFFIC, SELECTED_APPS }

data class ChameleonProfile(
    val address: String,
    val serverAddress: String,
    val serverDialAddress: String,
    val pskKey: String,
    val transportKey: String = "",
    /** The VPS's public IPv6 endpoint, tried before [serverDialAddress]; may be empty. */
    val serverDialAddress6: String = "",
    val realityServerPublicKey: String,
    val dohDomain: String,
    val bufferSize: Int,
    val reconnectInterval: Int,
    val mtu: Int,
    val certificateSha256: String = "",
)

data class HomeTunnelEnrollment(
    val address: String,
    val credentialEpoch: Int,
    val profile: ChameleonProfile,
    val prismLink: PrismLinkProfile,
)

data class PrismLinkProfile(
    val address: String,
    val pskKey: String,
    val relayAddress: String,
    val relayInstallationId: String,
    val relayRouteKey: String,
    val directAddress: String,
    val mtu: Int,
)

data class LinkedEasyPi(
    val serverUrl: String,
    val homeServerUrl: String?,
    val displayName: String,
    val mobileToken: String,
    val certificateSha256: String?,
    val photoFolder: String? = null,
    val backupVideos: Boolean = false,
)

class EasyPiLinkStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun linkedEasyPi(): LinkedEasyPi? {
        val serverUrl = read(KEY_SERVER) ?: return null
        val mobileToken = read(KEY_TOKEN) ?: return null
        return LinkedEasyPi(
            serverUrl = serverUrl,
            homeServerUrl = read(KEY_HOME_SERVER),
            displayName = read(KEY_NAME) ?: "Home EasyPi",
            mobileToken = mobileToken,
            certificateSha256 = read(KEY_CERTIFICATE),
            photoFolder = read(KEY_PHOTO_FOLDER),
            backupVideos = read(KEY_BACKUP_VIDEOS)?.toBooleanStrictOrNull() ?: false,
        )
    }

    fun save(link: LinkedEasyPi) {
        if (read(KEY_TOKEN)?.let { it != link.mobileToken } == true) preferences.edit().clear().apply()
        preferences.edit()
            .putString(KEY_SERVER, encrypt(link.serverUrl))
            .putString(KEY_NAME, encrypt(link.displayName))
            .putString(KEY_TOKEN, encrypt(link.mobileToken))
            .apply {
                if (link.homeServerUrl.isNullOrBlank()) remove(KEY_HOME_SERVER)
                else putString(KEY_HOME_SERVER, encrypt(link.homeServerUrl))
                if (link.certificateSha256.isNullOrBlank()) remove(KEY_CERTIFICATE)
                else putString(KEY_CERTIFICATE, encrypt(link.certificateSha256))
                if (link.photoFolder.isNullOrBlank()) remove(KEY_PHOTO_FOLDER)
                else putString(KEY_PHOTO_FOLDER, encrypt(link.photoFolder))
                putString(KEY_BACKUP_VIDEOS, encrypt(link.backupVideos.toString()))
            }
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()
    fun isLinked(): Boolean = linkedEasyPi() != null
    fun displayName(): String = linkedEasyPi()?.displayName ?: "Home EasyPi"
    fun chameleonProfile(): ChameleonProfile? {
        return ChameleonProfile(
            address = read(KEY_CHAMELEON_ADDRESS) ?: return null,
            serverAddress = read(KEY_CHAMELEON_SERVER) ?: return null,
            serverDialAddress = read(KEY_CHAMELEON_DIAL_ADDRESS) ?: "",
            serverDialAddress6 = read(KEY_CHAMELEON_DIAL_ADDRESS6) ?: "",
            pskKey = read(KEY_CHAMELEON_PSK) ?: return null,
            transportKey = read(KEY_CHAMELEON_TRANSPORT) ?: "",
            realityServerPublicKey = read(KEY_CHAMELEON_REALITY) ?: return null,
            dohDomain = read(KEY_CHAMELEON_DOH) ?: "",
            bufferSize = read(KEY_CHAMELEON_BUFFER)?.toIntOrNull() ?: 1000,
            reconnectInterval = read(KEY_CHAMELEON_RECONNECT)?.toIntOrNull() ?: 5,
            mtu = read(KEY_CHAMELEON_MTU)?.toIntOrNull() ?: 1420,
        )
    }

    fun saveChameleonProfile(profile: ChameleonProfile) {
        preferences.edit()
            .putString(KEY_CHAMELEON_ADDRESS, encrypt(profile.address))
            .putString(KEY_CHAMELEON_SERVER, encrypt(profile.serverAddress))
            .putString(KEY_CHAMELEON_DIAL_ADDRESS, encrypt(profile.serverDialAddress))
            .putString(KEY_CHAMELEON_DIAL_ADDRESS6, encrypt(profile.serverDialAddress6))
            .putString(KEY_CHAMELEON_PSK, encrypt(profile.pskKey))
            .putString(KEY_CHAMELEON_TRANSPORT, encrypt(profile.transportKey))
            .putString(KEY_CHAMELEON_REALITY, encrypt(profile.realityServerPublicKey))
            .putString(KEY_CHAMELEON_DOH, encrypt(profile.dohDomain))
            .putString(KEY_CHAMELEON_BUFFER, encrypt(profile.bufferSize.toString()))
            .putString(KEY_CHAMELEON_RECONNECT, encrypt(profile.reconnectInterval.toString()))
            .putString(KEY_CHAMELEON_MTU, encrypt(profile.mtu.toString()))
            .apply()
    }

    fun homeTunnelEnrollment(): HomeTunnelEnrollment? {
        val address = read(KEY_HOME_TUNNEL_ADDRESS) ?: return null
        val epoch = read(KEY_HOME_TUNNEL_EPOCH)?.toIntOrNull() ?: return null
        val profile = ChameleonProfile(
            address = address,
            serverAddress = read(KEY_HOME_TUNNEL_SERVER) ?: return null,
            serverDialAddress = read(KEY_HOME_TUNNEL_DIAL) ?: return null,
            pskKey = read(KEY_HOME_TUNNEL_PSK) ?: return null,
            transportKey = read(KEY_HOME_TUNNEL_TRANSPORT) ?: return null,
            realityServerPublicKey = read(KEY_HOME_TUNNEL_REALITY) ?: return null,
            dohDomain = "",
            bufferSize = read(KEY_HOME_TUNNEL_BUFFER)?.toIntOrNull() ?: 1000,
            reconnectInterval = read(KEY_HOME_TUNNEL_RECONNECT)?.toIntOrNull() ?: 5,
            mtu = read(KEY_HOME_TUNNEL_MTU)?.toIntOrNull() ?: 1200,
            certificateSha256 = read(KEY_HOME_TUNNEL_CERTIFICATE) ?: return null,
        )
        val prismLink = PrismLinkProfile(
            address = address,
            pskKey = profile.pskKey,
            relayAddress = read(KEY_HOME_PRISM_RELAY) ?: return null,
            relayInstallationId = read(KEY_HOME_PRISM_INSTALLATION) ?: return null,
            relayRouteKey = read(KEY_HOME_PRISM_ROUTE_KEY) ?: return null,
            directAddress = read(KEY_HOME_PRISM_DIRECT) ?: "",
            mtu = profile.mtu,
        )
        return HomeTunnelEnrollment(address = address, credentialEpoch = epoch, profile = profile, prismLink = prismLink)
    }

    fun saveHomeTunnelEnrollment(enrollment: MobileApiClient.HomeTunnelEnrollment) {
        require(enrollment.state == "active") { "Home Prism did not activate this phone" }
        preferences.edit()
            .putString(KEY_HOME_TUNNEL_ADDRESS, encrypt(enrollment.address))
            .putString(KEY_HOME_TUNNEL_EPOCH, encrypt(enrollment.credentialEpoch.toString()))
            .putString(KEY_HOME_TUNNEL_SERVER, encrypt(enrollment.serverAddress))
            .putString(KEY_HOME_TUNNEL_DIAL, encrypt(enrollment.serverDialAddress))
            .putString(KEY_HOME_TUNNEL_PSK, encrypt(enrollment.pskKey))
            .putString(KEY_HOME_TUNNEL_TRANSPORT, encrypt(enrollment.transportKey))
            .putString(KEY_HOME_TUNNEL_REALITY, encrypt(enrollment.realityServerPublicKey))
            .putString(KEY_HOME_TUNNEL_BUFFER, encrypt(enrollment.bufferSize.toString()))
            .putString(KEY_HOME_TUNNEL_RECONNECT, encrypt(enrollment.reconnectInterval.toString()))
            .putString(KEY_HOME_TUNNEL_MTU, encrypt(enrollment.mtu.toString()))
            .putString(KEY_HOME_TUNNEL_CERTIFICATE, encrypt(enrollment.certificateSha256))
            .putString(KEY_HOME_PRISM_RELAY, encrypt(enrollment.prismLinkRelayAddress))
            .putString(KEY_HOME_PRISM_INSTALLATION, encrypt(enrollment.prismLinkRelayInstallationId))
            .putString(KEY_HOME_PRISM_ROUTE_KEY, encrypt(enrollment.prismLinkRelayRouteKey))
            .putString(KEY_HOME_PRISM_DIRECT, encrypt(enrollment.prismLinkDirectAddress))
            .apply()
    }

    fun vpnRoutingMode(): VpnRoutingMode = runCatching {
        VpnRoutingMode.valueOf(read(KEY_VPN_ROUTING_MODE).orEmpty())
    }.getOrDefault(VpnRoutingMode.ALL_TRAFFIC)

    fun selectedVpnApps(): Set<String> = runCatching {
        val values = JSONArray(read(KEY_VPN_SELECTED_APPS) ?: "[]")
        buildSet {
            for (index in 0 until values.length()) add(values.getString(index))
        }
    }.getOrDefault(emptySet())

    fun saveVpnRouting(mode: VpnRoutingMode, packages: Set<String>) {
        val encoded = JSONArray(packages.sorted()).toString()
        preferences.edit()
            .putString(KEY_VPN_ROUTING_MODE, encrypt(mode.name))
            .putString(KEY_VPN_SELECTED_APPS, encrypt(encoded))
            .apply()
    }

    private fun read(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_SIZE, payload.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "easypi_link_secure"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "easypi-mobile-link-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE = 128
        const val KEY_SERVER = "server"
        const val KEY_HOME_SERVER = "home_server"
        const val KEY_NAME = "name"
        const val KEY_TOKEN = "token"
        const val KEY_CERTIFICATE = "certificate_sha256"
        const val KEY_PHOTO_FOLDER = "photo_folder"
        const val KEY_BACKUP_VIDEOS = "backup_videos"
        const val KEY_CHAMELEON_ADDRESS = "chameleon_address"
        const val KEY_CHAMELEON_SERVER = "chameleon_server"
        const val KEY_CHAMELEON_DIAL_ADDRESS = "chameleon_dial_address"
        const val KEY_CHAMELEON_DIAL_ADDRESS6 = "chameleon_dial_address6"
        const val KEY_CHAMELEON_PSK = "chameleon_psk"
        const val KEY_CHAMELEON_TRANSPORT = "chameleon_transport"
        const val KEY_CHAMELEON_REALITY = "chameleon_reality_public"
        const val KEY_CHAMELEON_DOH = "chameleon_doh"
        const val KEY_CHAMELEON_BUFFER = "chameleon_buffer"
        const val KEY_CHAMELEON_RECONNECT = "chameleon_reconnect"
        const val KEY_CHAMELEON_MTU = "chameleon_mtu"
        const val KEY_HOME_TUNNEL_ADDRESS = "home_tunnel_address"
        const val KEY_HOME_TUNNEL_EPOCH = "home_tunnel_credential_epoch"
        const val KEY_HOME_TUNNEL_SERVER = "home_tunnel_server"
        const val KEY_HOME_TUNNEL_DIAL = "home_tunnel_dial"
        const val KEY_HOME_TUNNEL_PSK = "home_tunnel_psk"
        const val KEY_HOME_TUNNEL_TRANSPORT = "home_tunnel_transport"
        const val KEY_HOME_TUNNEL_REALITY = "home_tunnel_reality"
        const val KEY_HOME_TUNNEL_BUFFER = "home_tunnel_buffer"
        const val KEY_HOME_TUNNEL_RECONNECT = "home_tunnel_reconnect"
        const val KEY_HOME_TUNNEL_MTU = "home_tunnel_mtu"
        const val KEY_HOME_TUNNEL_CERTIFICATE = "home_tunnel_certificate"
        const val KEY_HOME_PRISM_RELAY = "home_prism_relay"
        const val KEY_HOME_PRISM_INSTALLATION = "home_prism_installation"
        const val KEY_HOME_PRISM_ROUTE_KEY = "home_prism_route_key"
        const val KEY_HOME_PRISM_DIRECT = "home_prism_direct"
        const val KEY_VPN_ROUTING_MODE = "vpn_routing_mode"
        const val KEY_VPN_SELECTED_APPS = "vpn_selected_apps"
    }
}

