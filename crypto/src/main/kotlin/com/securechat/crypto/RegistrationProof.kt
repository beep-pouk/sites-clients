package com.securechat.crypto

/**
 * Proves to the relay server that a registration request for a given userId comes from the same
 * party that registered it, not merely someone who learned the userId. Without this, a relay
 * server's registration endpoint would let anyone silently overwrite another user's published
 * identity key, redirecting future first-contact conversations to an attacker (X3DH's signature
 * check on the signed prekey stops a forged bundle from being *usable*, but does nothing to stop
 * it from being *published* under someone else's userId in the first place).
 *
 * Shared between the app (which signs) and the server (which verifies) so the exact byte layout
 * being signed can never drift between the two independently-built modules.
 */
object RegistrationProof {
    private val INFO = "SecureChat-Registration-v1".toByteArray(Charsets.UTF_8)

    fun message(identitySigningKey: ByteArray, identityAgreementKey: ByteArray, signedPreKeyPublic: ByteArray): ByteArray =
        INFO + identitySigningKey + identityAgreementKey + signedPreKeyPublic

    fun sign(identity: IdentityKeyPair, signedPreKeyPublic: ByteArray): ByteArray = CryptoPrimitives.sign(
        identity.signingPrivateKey,
        message(identity.signingPublicKey, identity.agreementPublicKey, signedPreKeyPublic),
    )

    fun verify(
        identitySigningKey: ByteArray,
        identityAgreementKey: ByteArray,
        signedPreKeyPublic: ByteArray,
        signature: ByteArray,
    ): Boolean = CryptoPrimitives.verify(identitySigningKey, message(identitySigningKey, identityAgreementKey, signedPreKeyPublic), signature)
}
