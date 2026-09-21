package com.securechat.crypto

import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Thin wrappers around Tink's vetted primitive implementations (X25519, HKDF-HMAC-SHA256,
 * AES-256-GCM, Ed25519). Nothing in this file implements cryptography itself -- it only
 * exposes the primitives needed to build X3DH and the Double Ratchet per their public specs.
 */
object CryptoPrimitives {

    const val DH_KEY_LEN = 32
    const val AES_KEY_LEN = 32

    fun randomBytes(len: Int): ByteArray = Random.randBytes(len)

    // ---- X25519 Diffie-Hellman ----

    fun generateX25519PrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    fun x25519Agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
        X25519.computeSharedSecret(privateKey, peerPublicKey)

    // ---- Ed25519 signatures (used to sign the medium-term "signed prekey") ----

    data class SigningKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    fun generateSigningKeyPair(): SigningKeyPair {
        val kp = Ed25519Sign.KeyPair.newKeyPair()
        return SigningKeyPair(kp.publicKey, kp.privateKey)
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        Ed25519Sign(privateKey).sign(message)

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            Ed25519Verify(publicKey).verify(signature, message)
            true
        } catch (e: GeneralSecurityException) {
            false
        }

    // ---- HKDF-SHA256 ----

    fun hkdf(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", inputKeyMaterial, salt, info, outputLength)

    // ---- AES-256-GCM AEAD (nonce is generated and packed internally by Tink) ----

    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        AesGcmJce(key).encrypt(plaintext, associatedData)

    fun aesGcmDecrypt(key: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        AesGcmJce(key).decrypt(ciphertext, associatedData)

    // ---- constant-time comparison, for fingerprint / safety-number checks ----

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // ---- HMAC-SHA256, used for the Double Ratchet's symmetric-key (chain) ratchet ----

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
