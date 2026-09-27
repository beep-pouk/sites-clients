package com.securechat.app.data.repository

import com.securechat.app.data.local.MessageDao
import com.securechat.app.data.local.MessageDirection
import com.securechat.app.data.local.MessageEntity
import com.securechat.app.data.local.PeerLastMessage
import com.securechat.app.data.local.SessionDao
import com.securechat.app.data.local.SessionEntity
import com.securechat.app.data.remote.AckRequest
import com.securechat.app.data.remote.HandshakeEnvelopeDto
import com.securechat.app.data.remote.OwnershipAuth
import com.securechat.app.data.remote.RatchetHeaderDto
import com.securechat.app.data.remote.RatchetMessageDto
import com.securechat.app.data.remote.SendMessageRequest
import com.securechat.app.data.remote.ServerApi
import com.securechat.app.data.remote.requireSuccessful
import com.securechat.crypto.PublicIdentity
import com.securechat.crypto.RatchetHeader
import com.securechat.crypto.RatchetMessage
import com.securechat.crypto.SecureChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Raised when a peer's identity key no longer matches the one pinned locally (from a QR
 * exchange or a prior conversation) - the server-relayed conversation may be under attack by a
 * party impersonating that peer. The UI should block sending/reading until the user re-verifies.
 */
class IdentityKeyChangedException(val peerUserId: String) :
    Exception("The identity key for $peerUserId has changed since it was last seen - possible impersonation.")

/**
 * Orchestrates end-to-end encrypted messaging: establishing sessions (via X3DH on first contact),
 * running the Double Ratchet for every subsequent message, and persisting both session state and
 * decrypted message history to the local encrypted database.
 *
 * A single [sessionLock] serializes all session mutation. Traffic volumes here are low (one
 * person's conversations) so a global lock is simpler than per-conversation locking and avoids
 * ever corrupting ratchet state by interleaving a send with an incoming sync.
 */
class ChatRepository(
    private val keyRepository: KeyRepository,
    private val contactRepository: ContactRepository,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val serverApi: ServerApi,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionLock = Mutex()

    fun observeConversation(peerUserId: String): Flow<List<MessageEntity>> = messageDao.observeConversation(peerUserId)

    fun observeConversationSummaries(): Flow<List<PeerLastMessage>> = messageDao.observeLastMessagePerConversation()

    suspend fun sendMessage(peerUserId: String, plaintext: String) = sessionLock.withLock {
        val existing = sessionDao.find(peerUserId)
        if (existing == null) sendAsNewSession(peerUserId, plaintext) else sendWithExistingSession(peerUserId, existing, plaintext)

        messageDao.insert(
            MessageEntity(
                peerUserId = peerUserId,
                direction = MessageDirection.SENT,
                body = plaintext,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun sendAsNewSession(peerUserId: String, plaintext: String) {
        val bundle = keyRepository.fetchBundleFor(peerUserId)
        assertIdentityMatchesPinnedContact(peerUserId, bundle.identity)

        val (session, handshake) = SecureChatSession.startAsInitiator(
            selfUserId = keyRepository.localUserId,
            selfIdentity = keyRepository.loadIdentity(),
            peerBundle = bundle,
            firstPlaintext = plaintext.toByteArray(Charsets.UTF_8),
        )
        persistSession(peerUserId, session, localWasInitiator = true)
        contactRepository.addUnknownContactIfMissing(peerUserId, bundle.identity)

        val envelope = HandshakeEnvelopeDto(
            initiatorUserId = handshake.initiatorUserId,
            initiatorIdentitySigningKey = encode(handshake.initiatorIdentity.signingPublicKey),
            initiatorIdentityAgreementKey = encode(handshake.initiatorIdentity.agreementPublicKey),
            initiatorEphemeralPublicKey = encode(handshake.initiatorEphemeralPublicKey),
            usedOneTimePreKeyId = handshake.usedOneTimePreKeyId,
            firstMessage = handshake.firstMessage.toDto(),
        )
        sendSigned(peerUserId, json.encodeToString(envelope), isHandshake = true)
    }

    private suspend fun sendWithExistingSession(peerUserId: String, sessionEntity: SessionEntity, plaintext: String) {
        val pinnedIdentity = contactRepository.findContact(peerUserId)?.identity
            ?: error("No saved contact for $peerUserId; cannot resume its session")
        val session = restoreSession(peerUserId, sessionEntity, pinnedIdentity)
        val message = session.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        persistSession(peerUserId, session, sessionEntity.localWasInitiator)

        sendSigned(peerUserId, json.encodeToString(message.toDto()), isHandshake = false)
    }

    private suspend fun sendSigned(peerUserId: String, ciphertextEnvelope: String, isHandshake: Boolean) {
        val auth = OwnershipAuth.current(keyRepository.loadIdentity(), keyRepository.localUserId)
        serverApi.sendMessage(
            ts = auth.timestamp,
            sig = auth.signature,
            request = SendMessageRequest(
                recipientUserId = peerUserId,
                senderUserId = keyRepository.localUserId,
                ciphertextEnvelope = ciphertextEnvelope,
                isHandshake = isHandshake,
            ),
        ).requireSuccessful("send message")
    }

    /** Fetches, decrypts, and stores any messages queued for the local user; call periodically. */
    suspend fun syncIncomingMessages() = sessionLock.withLock {
        val identity = keyRepository.loadIdentity()
        val fetchAuth = OwnershipAuth.current(identity, keyRepository.localUserId)
        val pending = serverApi.fetchMessages(keyRepository.localUserId, fetchAuth.timestamp, fetchAuth.signature).body().orEmpty()
        if (pending.isEmpty()) return@withLock

        for (stored in pending) {
            // Ack regardless of success below: a message that can't be decrypted (corrupt,
            // replayed, or genuinely tampered with) will never succeed on retry either, and
            // leaving it un-acked would wedge every later message behind it forever.
            runCatching {
                if (stored.isHandshake) {
                    receiveHandshake(stored.senderUserId, stored.ciphertextEnvelope)
                } else {
                    receiveMessage(stored.senderUserId, stored.ciphertextEnvelope)
                }
            }
        }
        val ackAuth = OwnershipAuth.current(identity, keyRepository.localUserId)
        serverApi.acknowledgeMessages(keyRepository.localUserId, ackAuth.timestamp, ackAuth.signature, AckRequest(pending.map { it.messageId }))
            .requireSuccessful("acknowledge messages")
        keyRepository.replenishOneTimePreKeysIfLow()
    }

    private suspend fun receiveHandshake(senderUserId: String, envelopeJson: String) {
        val envelope = json.decodeFromString<HandshakeEnvelopeDto>(envelopeJson)
        val senderIdentity = PublicIdentity(
            decode(envelope.initiatorIdentitySigningKey),
            decode(envelope.initiatorIdentityAgreementKey),
        )
        assertIdentityMatchesPinnedContact(senderUserId, senderIdentity)

        val oneTimePreKey = keyRepository.consumeOneTimePreKey(envelope.usedOneTimePreKeyId)
        val (session, plaintext) = SecureChatSession.startAsResponder(
            selfIdentity = keyRepository.loadIdentity(),
            selfSignedPreKey = keyRepository.loadSignedPreKey(),
            selfOneTimePreKey = oneTimePreKey,
            handshake = SecureChatSession.InitialHandshake(
                initiatorUserId = envelope.initiatorUserId,
                initiatorIdentity = senderIdentity,
                initiatorEphemeralPublicKey = decode(envelope.initiatorEphemeralPublicKey),
                usedOneTimePreKeyId = envelope.usedOneTimePreKeyId,
                firstMessage = envelope.firstMessage.toDomain(),
            ),
        )
        persistSession(senderUserId, session, localWasInitiator = false)
        contactRepository.addUnknownContactIfMissing(senderUserId, senderIdentity)
        storeReceivedMessage(senderUserId, plaintext)
    }

    private suspend fun receiveMessage(senderUserId: String, envelopeJson: String) {
        val sessionEntity = sessionDao.find(senderUserId)
            ?: error("Received a message from $senderUserId with no established session")
        val pinnedIdentity = contactRepository.findContact(senderUserId)?.identity
            ?: error("No saved contact for $senderUserId; cannot resume its session")
        val session = restoreSession(senderUserId, sessionEntity, pinnedIdentity)

        val message = json.decodeFromString<RatchetMessageDto>(envelopeJson).toDomain()
        val plaintext = session.decrypt(message)
        persistSession(senderUserId, session, sessionEntity.localWasInitiator)
        storeReceivedMessage(senderUserId, plaintext)
    }

    private suspend fun storeReceivedMessage(senderUserId: String, plaintext: ByteArray) {
        messageDao.insert(
            MessageEntity(
                peerUserId = senderUserId,
                direction = MessageDirection.RECEIVED,
                body = String(plaintext, Charsets.UTF_8),
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun restoreSession(peerUserId: String, entity: SessionEntity, pinnedPeerIdentity: PublicIdentity): SecureChatSession =
        SecureChatSession.resume(
            peerUserId = peerUserId,
            state = SessionCodec.decodeState(entity.stateJson),
            localIdentity = keyRepository.loadPublicIdentity(),
            remoteIdentity = pinnedPeerIdentity,
            localWasInitiator = entity.localWasInitiator,
        )

    private suspend fun persistSession(peerUserId: String, session: SecureChatSession, localWasInitiator: Boolean) {
        sessionDao.upsert(
            SessionEntity(
                peerUserId = peerUserId,
                stateJson = SessionCodec.encodeState(session.exportState()),
                localWasInitiator = localWasInitiator,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun assertIdentityMatchesPinnedContact(peerUserId: String, identity: PublicIdentity) {
        val pinned = contactRepository.findContact(peerUserId) ?: return
        val matches = pinned.identity.signingPublicKey.contentEquals(identity.signingPublicKey) &&
            pinned.identity.agreementPublicKey.contentEquals(identity.agreementPublicKey)
        if (!matches) throw IdentityKeyChangedException(peerUserId)
    }
}

private fun RatchetMessage.toDto() = RatchetMessageDto(
    header = RatchetHeaderDto(encode(header.dhPublicKey), header.previousChainLength, header.messageNumber),
    ciphertext = encode(ciphertext),
)

private fun RatchetMessageDto.toDomain() = RatchetMessage(
    header = RatchetHeader(decode(header.dhPublicKey), header.previousChainLength, header.messageNumber),
    ciphertext = decode(ciphertext),
)
