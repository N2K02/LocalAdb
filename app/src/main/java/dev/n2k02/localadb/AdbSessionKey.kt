package dev.n2k02.localadb

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/** New disposable RSA key object per embedded ADB manager. */
internal object AdbSessionKey {
    fun copyOf(persistent: PrivateKey): PrivateKey {
        val encoded = requireNotNull(persistent.encoded) {
            "ADB identity must be exportable for session reuse"
        }
        return KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(encoded))
    }
}
