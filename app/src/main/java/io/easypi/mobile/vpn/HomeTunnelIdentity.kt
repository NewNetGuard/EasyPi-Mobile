package io.easypi.mobile.vpn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

data class HomeTunnelIdentityProof(
    val publicKey: String,
    val signature: String,
)

object HomeTunnelIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "easypi-home-tunnel-identity-v1"

    fun proof(deviceId: String): HomeTunnelIdentityProof {
        require(deviceId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid mobile device identity" }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) ?: run {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
                initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKeyPair().private
            }
        }
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: error("Android Keystore did not return the tunnel identity")
        val encodedPublicKey = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey as java.security.PrivateKey)
            update(enrollmentStatement(deviceId, encodedPublicKey))
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
        return HomeTunnelIdentityProof(encodedPublicKey, signature)
    }

    internal fun enrollmentStatement(deviceId: String, publicKey: String): ByteArray =
        "easypi/home-tunnel/enroll/v1\n$deviceId\n$publicKey".toByteArray(Charsets.US_ASCII)
}
