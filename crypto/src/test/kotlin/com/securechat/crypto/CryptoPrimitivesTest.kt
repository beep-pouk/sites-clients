package com.securechat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoPrimitivesTest {

    @Test
    fun `x25519 agreement is symmetric`() {
        val alicePriv = CryptoPrimitives.generateX25519PrivateKey()
        val alicePub = CryptoPrimitives.x25519PublicFromPrivate(alicePriv)
        val bobPriv = CryptoPrimitives.generateX25519PrivateKey()
        val bobPub = CryptoPrimitives.x25519PublicFromPrivate(bobPriv)

        val aliceShared = CryptoPrimitives.x25519Agree(alicePriv, bobPub)
        val bobShared = CryptoPrimitives.x25519Agree(bobPriv, alicePub)

        assertArrayEquals(aliceShared, bobShared)
    }

    @Test
    fun `ed25519 signatures verify only with the matching public key and unmodified message`() {
        val keyPair = CryptoPrimitives.generateSigningKeyPair()
        val other = CryptoPrimitives.generateSigningKeyPair()
        val message = "prekey-bytes".toByteArray()
        val signature = CryptoPrimitives.sign(keyPair.privateKey, message)

        assertTrue(CryptoPrimitives.verify(keyPair.publicKey, message, signature))
        assertFalse(CryptoPrimitives.verify(other.publicKey, message, signature))
        assertFalse(CryptoPrimitives.verify(keyPair.publicKey, "tampered-bytes".toByteArray(), signature))
    }

    @Test
    fun `aes-gcm decrypt fails when associated data does not match`() {
        val key = CryptoPrimitives.randomBytes(32)
        val ciphertext = CryptoPrimitives.aesGcmEncrypt(key, "payload".toByteArray(), "header-v1".toByteArray())

        assertArrayEquals(
            "payload".toByteArray(),
            CryptoPrimitives.aesGcmDecrypt(key, ciphertext, "header-v1".toByteArray()),
        )
        org.junit.Assert.assertThrows(Exception::class.java) {
            CryptoPrimitives.aesGcmDecrypt(key, ciphertext, "header-v2".toByteArray())
        }
    }

    @Test
    fun `hkdf output is deterministic and sensitive to every input`() {
        val ikm = CryptoPrimitives.randomBytes(32)
        val salt = CryptoPrimitives.randomBytes(32)
        val info = "info".toByteArray()

        val out1 = CryptoPrimitives.hkdf(ikm, salt, info, 32)
        val out2 = CryptoPrimitives.hkdf(ikm, salt, info, 32)
        assertArrayEquals(out1, out2)

        val differentInfo = CryptoPrimitives.hkdf(ikm, salt, "other".toByteArray(), 32)
        assertNotEquals(out1.toList(), differentInfo.toList())
    }

    @Test
    fun `hmac chain-ratchet outputs differ for message key vs next chain key`() {
        val chainKey = CryptoPrimitives.randomBytes(32)
        val messageKey = CryptoPrimitives.hmacSha256(chainKey, byteArrayOf(0x01))
        val nextChainKey = CryptoPrimitives.hmacSha256(chainKey, byteArrayOf(0x02))
        assertNotEquals(messageKey.toList(), nextChainKey.toList())
    }
}
