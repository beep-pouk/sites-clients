package com.securechat.crypto

/**
 * Glues X3DH key agreement to a [RatchetSession] and fixes the associated data (both parties'
 * long-term identity keys) that authenticates every message header, so callers get a session
 * object they can just call [encrypt]/[decrypt] on without re-deriving these details themselves.
 */
class SecureChatSession private constructor(
    val peerUserId: String,
    private val ratchet: RatchetSession,
    private val associatedData: ByteArray,
) {
    fun encrypt(plaintext: ByteArray): RatchetMessage = ratchet.encrypt(plaintext, associatedData)

    fun decrypt(message: RatchetMessage): ByteArray = ratchet.decrypt(message, associatedData)

    fun currentRatchetPublicKey(): ByteArray = ratchet.currentPublicKey()

    fun exportState(): RatchetSessionState = ratchet.exportState()

    /** Everything Bob needs, alongside his own keys, to accept Alice's first message. */
    data class InitialHandshake(
        val initiatorUserId: String,
        val initiatorIdentity: PublicIdentity,
        val initiatorEphemeralPublicKey: ByteArray,
        val usedOneTimePreKeyId: Int?,
        val firstMessage: RatchetMessage,
    )

    companion object {
        fun startAsInitiator(
            selfUserId: String,
            selfIdentity: IdentityKeyPair,
            peerBundle: PreKeyBundle,
            firstPlaintext: ByteArray,
        ): Pair<SecureChatSession, InitialHandshake> {
            val agreement = X3DH.initiateAsAlice(selfIdentity, peerBundle)
            val ratchet = RatchetSession.initAlice(agreement.sharedSecret, peerBundle.signedPreKey.publicKey)
            val ad = associatedData(selfIdentity.publicIdentity(), peerBundle.identity)

            val firstMessage = ratchet.encrypt(firstPlaintext, ad)
            val session = SecureChatSession(peerBundle.userId, ratchet, ad)
            val handshake = InitialHandshake(
                initiatorUserId = selfUserId,
                initiatorIdentity = selfIdentity.publicIdentity(),
                initiatorEphemeralPublicKey = agreement.ephemeralPublicKey,
                usedOneTimePreKeyId = agreement.usedOneTimePreKeyId,
                firstMessage = firstMessage,
            )
            return session to handshake
        }

        /** Returns the new session plus the decrypted plaintext of Alice's first message. */
        fun startAsResponder(
            selfIdentity: IdentityKeyPair,
            selfSignedPreKey: SignedPreKeyPair,
            selfOneTimePreKey: OneTimePreKeyPair?,
            handshake: InitialHandshake,
        ): Pair<SecureChatSession, ByteArray> {
            val sharedSecret = X3DH.respondAsBob(
                bobIdentity = selfIdentity,
                bobSignedPreKey = selfSignedPreKey,
                bobOneTimePreKey = selfOneTimePreKey,
                aliceIdentityAgreementKey = handshake.initiatorIdentity.agreementPublicKey,
                aliceEphemeralPublicKey = handshake.initiatorEphemeralPublicKey,
            )
            val ratchet = RatchetSession.initBob(sharedSecret, selfSignedPreKey)
            val ad = associatedData(handshake.initiatorIdentity, selfIdentity.publicIdentity())

            val plaintext = ratchet.decrypt(handshake.firstMessage, ad)
            val session = SecureChatSession(handshake.initiatorUserId, ratchet, ad)
            return session to plaintext
        }

        /** Rehydrates a session previously persisted via [exportState], e.g. after an app restart. */
        fun resume(
            peerUserId: String,
            state: RatchetSessionState,
            localIdentity: PublicIdentity,
            remoteIdentity: PublicIdentity,
            localWasInitiator: Boolean,
        ): SecureChatSession {
            val ad = if (localWasInitiator) {
                associatedData(localIdentity, remoteIdentity)
            } else {
                associatedData(remoteIdentity, localIdentity)
            }
            return SecureChatSession(peerUserId, RatchetSession.restore(state), ad)
        }

        private fun associatedData(initiatorIdentity: PublicIdentity, responderIdentity: PublicIdentity): ByteArray =
            initiatorIdentity.agreementPublicKey + initiatorIdentity.signingPublicKey +
                responderIdentity.agreementPublicKey + responderIdentity.signingPublicKey
    }
}
