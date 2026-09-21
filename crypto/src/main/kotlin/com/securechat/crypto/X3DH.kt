package com.securechat.crypto

/**
 * Extended Triple Diffie-Hellman key agreement (https://signal.org/docs/specifications/x3dh/).
 *
 * Lets Alice establish a shared secret with Bob using only a [PreKeyBundle] fetched from the
 * relay server, without Bob needing to be online. The resulting secret seeds a [RatchetSession].
 *
 * Simplification vs. the full spec: this implementation does not perform the small-order/
 * contributory-behaviour point checks the reference spec recommends for legacy curves, since
 * X25519 (RFC 7748) already clears the cofactor and Tink's implementation rejects low-order
 * inputs. Production systems handling hostile networks should still prefer a fully audited
 * implementation such as libsignal; this module aims to be a correct, readable reference.
 */
object X3DH {
    private val INFO = "SecureChat-X3DH-v1".toByteArray(Charsets.UTF_8)
    private val ZERO_SALT = ByteArray(32)

    class InvalidPreKeySignature : Exception("Signed prekey signature does not verify against the sender's identity key")

    data class InitiationResult(
        val sharedSecret: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val usedOneTimePreKeyId: Int?,
    )

    fun verifyBundle(bundle: PreKeyBundle): Boolean = CryptoPrimitives.verify(
        bundle.identity.signingPublicKey,
        bundle.signedPreKey.publicKey,
        bundle.signedPreKey.signature,
    )

    /** Alice's side: called once, when starting a conversation with Bob using his published bundle. */
    fun initiateAsAlice(aliceIdentity: IdentityKeyPair, bobBundle: PreKeyBundle): InitiationResult {
        if (!verifyBundle(bobBundle)) throw InvalidPreKeySignature()

        val ephemeralPrivateKey = CryptoPrimitives.generateX25519PrivateKey()
        val ephemeralPublicKey = CryptoPrimitives.x25519PublicFromPrivate(ephemeralPrivateKey)

        val dh1 = CryptoPrimitives.x25519Agree(aliceIdentity.agreementPrivateKey, bobBundle.signedPreKey.publicKey)
        val dh2 = CryptoPrimitives.x25519Agree(ephemeralPrivateKey, bobBundle.identity.agreementPublicKey)
        val dh3 = CryptoPrimitives.x25519Agree(ephemeralPrivateKey, bobBundle.signedPreKey.publicKey)
        val dh4 = bobBundle.oneTimePreKey?.let { CryptoPrimitives.x25519Agree(ephemeralPrivateKey, it.publicKey) }

        val ikm = dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
        val sharedSecret = CryptoPrimitives.hkdf(ikm, ZERO_SALT, INFO, 32)

        return InitiationResult(sharedSecret, ephemeralPublicKey, bobBundle.oneTimePreKey?.keyId)
    }

    /** Bob's side: called on receipt of Alice's first message, which carries her identity + ephemeral keys. */
    fun respondAsBob(
        bobIdentity: IdentityKeyPair,
        bobSignedPreKey: SignedPreKeyPair,
        bobOneTimePreKey: OneTimePreKeyPair?,
        aliceIdentityAgreementKey: ByteArray,
        aliceEphemeralPublicKey: ByteArray,
    ): ByteArray {
        val dh1 = CryptoPrimitives.x25519Agree(bobSignedPreKey.privateKey, aliceIdentityAgreementKey)
        val dh2 = CryptoPrimitives.x25519Agree(bobIdentity.agreementPrivateKey, aliceEphemeralPublicKey)
        val dh3 = CryptoPrimitives.x25519Agree(bobSignedPreKey.privateKey, aliceEphemeralPublicKey)
        val dh4 = bobOneTimePreKey?.let { CryptoPrimitives.x25519Agree(it.privateKey, aliceEphemeralPublicKey) }

        val ikm = dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
        return CryptoPrimitives.hkdf(ikm, ZERO_SALT, INFO, 32)
    }
}
