package com.securechat.crypto

/**
 * A user's long-term identity: an Ed25519 signing key (used only to authenticate the
 * medium-term signed prekey) and a long-term X25519 agreement key (used in X3DH).
 * Private key material never leaves the device; only [publicIdentity] is ever published.
 */
class IdentityKeyPair private constructor(
    val signingPublicKey: ByteArray,
    val signingPrivateKey: ByteArray,
    val agreementPublicKey: ByteArray,
    val agreementPrivateKey: ByteArray,
) {
    fun publicIdentity() = PublicIdentity(signingPublicKey, agreementPublicKey)

    companion object {
        fun generate(): IdentityKeyPair {
            val signing = CryptoPrimitives.generateSigningKeyPair()
            val agreementPriv = CryptoPrimitives.generateX25519PrivateKey()
            val agreementPub = CryptoPrimitives.x25519PublicFromPrivate(agreementPriv)
            return IdentityKeyPair(signing.publicKey, signing.privateKey, agreementPub, agreementPriv)
        }

        /** Reconstructs a key pair from bytes previously persisted in encrypted local storage. */
        fun restore(
            signingPublicKey: ByteArray,
            signingPrivateKey: ByteArray,
            agreementPublicKey: ByteArray,
            agreementPrivateKey: ByteArray,
        ) = IdentityKeyPair(signingPublicKey, signingPrivateKey, agreementPublicKey, agreementPrivateKey)
    }
}

data class PublicIdentity(val signingPublicKey: ByteArray, val agreementPublicKey: ByteArray)

/** Medium-term X25519 key, rotated periodically, signed by the identity signing key. */
class SignedPreKeyPair private constructor(
    val keyId: Int,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val signature: ByteArray,
) {
    fun toPublic() = SignedPreKeyPublic(keyId, publicKey, signature)

    companion object {
        fun generate(identity: IdentityKeyPair, keyId: Int): SignedPreKeyPair {
            val priv = CryptoPrimitives.generateX25519PrivateKey()
            val pub = CryptoPrimitives.x25519PublicFromPrivate(priv)
            val signature = CryptoPrimitives.sign(identity.signingPrivateKey, pub)
            return SignedPreKeyPair(keyId, pub, priv, signature)
        }

        fun restore(keyId: Int, publicKey: ByteArray, privateKey: ByteArray, signature: ByteArray) =
            SignedPreKeyPair(keyId, publicKey, privateKey, signature)
    }
}

data class SignedPreKeyPublic(val keyId: Int, val publicKey: ByteArray, val signature: ByteArray)

/** Single-use X25519 key. Consumed (and discarded) the first time a peer initiates a session with it. */
class OneTimePreKeyPair private constructor(val keyId: Int, val publicKey: ByteArray, val privateKey: ByteArray) {
    fun toPublic() = OneTimePreKeyPublic(keyId, publicKey)

    companion object {
        fun generateBatch(startId: Int, count: Int): List<OneTimePreKeyPair> =
            (0 until count).map { offset ->
                val priv = CryptoPrimitives.generateX25519PrivateKey()
                OneTimePreKeyPair(startId + offset, CryptoPrimitives.x25519PublicFromPrivate(priv), priv)
            }

        fun restore(keyId: Int, publicKey: ByteArray, privateKey: ByteArray) =
            OneTimePreKeyPair(keyId, publicKey, privateKey)
    }
}

data class OneTimePreKeyPublic(val keyId: Int, val publicKey: ByteArray)

/**
 * Everything an initiator needs to start an encrypted session with a peer without the peer
 * being online: their identity, a signed prekey, and (ideally) a one-time prekey. This is what
 * the relay server hands out and what a QR code can carry for a first, in-person exchange.
 */
data class PreKeyBundle(
    val userId: String,
    val identity: PublicIdentity,
    val signedPreKey: SignedPreKeyPublic,
    val oneTimePreKey: OneTimePreKeyPublic?,
)
