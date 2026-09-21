package com.securechat.crypto

import java.nio.ByteBuffer
import java.util.Base64

/** Message header: the sender's current ratchet public key plus chain-position bookkeeping. */
data class RatchetHeader(
    val dhPublicKey: ByteArray,
    val previousChainLength: Int,
    val messageNumber: Int,
) {
    fun encode(): ByteArray =
        ByteBuffer.allocate(dhPublicKey.size + 8)
            .put(dhPublicKey)
            .putInt(previousChainLength)
            .putInt(messageNumber)
            .array()

    companion object {
        fun decode(bytes: ByteArray): RatchetHeader {
            val buffer = ByteBuffer.wrap(bytes)
            val dhKey = ByteArray(CryptoPrimitives.DH_KEY_LEN)
            buffer.get(dhKey)
            val pn = buffer.int
            val n = buffer.int
            return RatchetHeader(dhKey, pn, n)
        }
    }
}

data class RatchetMessage(val header: RatchetHeader, val ciphertext: ByteArray)

/**
 * The Double Ratchet algorithm (https://signal.org/docs/specifications/doubleratchet/),
 * implemented against the primitives in [CryptoPrimitives]. Provides forward secrecy (old
 * message keys are erased as soon as they're used) and break-in recovery / post-compromise
 * security (a fresh DH ratchet step is mixed in on every direction change of the conversation).
 *
 * This class implements the "associated-data" variant of the spec: headers are sent in the
 * clear but authenticated as AEAD associated data, rather than the (more complex) header
 * encryption variant. This matches the spec's own "non-header-encrypted" reference design.
 *
 * Not thread-safe; callers must serialize access to a given session (e.g. one coroutine /
 * one DB transaction per conversation).
 */
class RatchetSession private constructor(
    private var selfRatchetPrivateKey: ByteArray,
    private var selfRatchetPublicKey: ByteArray,
    private var remoteRatchetPublicKey: ByteArray?,
    private var rootKey: ByteArray,
    private var sendingChainKey: ByteArray?,
    private var receivingChainKey: ByteArray?,
    private var sendMessageNumber: Int = 0,
    private var receiveMessageNumber: Int = 0,
    private var previousSendingChainLength: Int = 0,
    private val skippedMessageKeys: MutableMap<String, ByteArray> = mutableMapOf(),
) {

    class TooManySkippedMessages :
        Exception("Refusing to derive more than $MAX_SKIPPED_MESSAGE_KEYS skipped message keys (possible attack or corrupted state)")

    class MessageAuthenticationFailed : Exception("Ciphertext failed authentication - message was tampered with or session state has diverged")

    fun currentPublicKey(): ByteArray = selfRatchetPublicKey.copyOf()

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): RatchetMessage {
        val (newChainKey, messageKey) = kdfChain(sendingChainKey!!)
        sendingChainKey = newChainKey

        val header = RatchetHeader(selfRatchetPublicKey, previousSendingChainLength, sendMessageNumber)
        sendMessageNumber += 1

        val ciphertext = CryptoPrimitives.aesGcmEncrypt(messageKey, plaintext, associatedData + header.encode())
        return RatchetMessage(header, ciphertext)
    }

    fun decrypt(message: RatchetMessage, associatedData: ByteArray): ByteArray {
        tryDecryptWithSkippedKey(message, associatedData)?.let { return it }

        if (!message.header.dhPublicKey.contentEquals(remoteRatchetPublicKey)) {
            skipMessageKeys(message.header.previousChainLength)
            performDhRatchetStep(message.header.dhPublicKey)
        }

        skipMessageKeys(message.header.messageNumber)

        val (newChainKey, messageKey) = kdfChain(receivingChainKey!!)
        receivingChainKey = newChainKey
        receiveMessageNumber += 1

        return decryptOrThrow(messageKey, message, associatedData)
    }

    private fun tryDecryptWithSkippedKey(message: RatchetMessage, associatedData: ByteArray): ByteArray? {
        val key = skippedKeyId(message.header.dhPublicKey, message.header.messageNumber)
        val messageKey = skippedMessageKeys.remove(key) ?: return null
        return decryptOrThrow(messageKey, message, associatedData)
    }

    private fun decryptOrThrow(messageKey: ByteArray, message: RatchetMessage, associatedData: ByteArray): ByteArray =
        try {
            CryptoPrimitives.aesGcmDecrypt(messageKey, message.ciphertext, associatedData + message.header.encode())
        } catch (e: Exception) {
            throw MessageAuthenticationFailed()
        }

    private fun skipMessageKeys(until: Int) {
        val remote = remoteRatchetPublicKey ?: return
        val chainKey = receivingChainKey ?: return
        if (receiveMessageNumber + MAX_SKIPPED_MESSAGE_KEYS < until) throw TooManySkippedMessages()

        var currentChainKey = chainKey
        var n = receiveMessageNumber
        while (n < until) {
            val (nextChainKey, messageKey) = kdfChain(currentChainKey)
            skippedMessageKeys[skippedKeyId(remote, n)] = messageKey
            currentChainKey = nextChainKey
            n += 1
        }
        receivingChainKey = currentChainKey
        receiveMessageNumber = n
    }

    private fun performDhRatchetStep(newRemotePublicKey: ByteArray) {
        previousSendingChainLength = sendMessageNumber
        sendMessageNumber = 0
        receiveMessageNumber = 0
        remoteRatchetPublicKey = newRemotePublicKey

        val (rkAfterReceive, ck1) = kdfRootKey(rootKey, CryptoPrimitives.x25519Agree(selfRatchetPrivateKey, newRemotePublicKey))
        rootKey = rkAfterReceive
        receivingChainKey = ck1

        selfRatchetPrivateKey = CryptoPrimitives.generateX25519PrivateKey()
        selfRatchetPublicKey = CryptoPrimitives.x25519PublicFromPrivate(selfRatchetPrivateKey)

        val (rkAfterSend, ck2) = kdfRootKey(rootKey, CryptoPrimitives.x25519Agree(selfRatchetPrivateKey, newRemotePublicKey))
        rootKey = rkAfterSend
        sendingChainKey = ck2
    }

    /** Persists the full session state so a conversation can resume across app restarts. */
    fun exportState(): RatchetSessionState = RatchetSessionState(
        selfRatchetPrivateKey = selfRatchetPrivateKey,
        selfRatchetPublicKey = selfRatchetPublicKey,
        remoteRatchetPublicKey = remoteRatchetPublicKey,
        rootKey = rootKey,
        sendingChainKey = sendingChainKey,
        receivingChainKey = receivingChainKey,
        sendMessageNumber = sendMessageNumber,
        receiveMessageNumber = receiveMessageNumber,
        previousSendingChainLength = previousSendingChainLength,
        skippedMessageKeys = skippedMessageKeys.toMap(),
    )

    companion object {
        const val MAX_SKIPPED_MESSAGE_KEYS = 1000

        private val ROOT_INFO = "SecureChat-Ratchet-Root".toByteArray(Charsets.UTF_8)
        private val CHAIN_MESSAGE_KEY_CONSTANT = byteArrayOf(0x01)
        private val CHAIN_NEXT_KEY_CONSTANT = byteArrayOf(0x02)

        private fun kdfRootKey(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
            val output = CryptoPrimitives.hkdf(dhOutput, rootKey, ROOT_INFO, 64)
            return output.copyOfRange(0, 32) to output.copyOfRange(32, 64)
        }

        private fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
            val messageKey = CryptoPrimitives.hmacSha256(chainKey, CHAIN_MESSAGE_KEY_CONSTANT)
            val nextChainKey = CryptoPrimitives.hmacSha256(chainKey, CHAIN_NEXT_KEY_CONSTANT)
            return nextChainKey to messageKey
        }

        private fun skippedKeyId(dhPublicKey: ByteArray, messageNumber: Int): String =
            Base64.getEncoder().encodeToString(dhPublicKey) + ":" + messageNumber

        /** Alice's side: she knows Bob's (signed prekey) public ratchet key from his bundle up front. */
        fun initAlice(sharedSecret: ByteArray, bobInitialRatchetPublicKey: ByteArray): RatchetSession {
            val selfPriv = CryptoPrimitives.generateX25519PrivateKey()
            val selfPub = CryptoPrimitives.x25519PublicFromPrivate(selfPriv)
            val (rootKeyAfter, sendingChainKey) =
                kdfRootKey(sharedSecret, CryptoPrimitives.x25519Agree(selfPriv, bobInitialRatchetPublicKey))

            return RatchetSession(
                selfRatchetPrivateKey = selfPriv,
                selfRatchetPublicKey = selfPub,
                remoteRatchetPublicKey = bobInitialRatchetPublicKey,
                rootKey = rootKeyAfter,
                sendingChainKey = sendingChainKey,
                receivingChainKey = null,
            )
        }

        /** Bob's side: he learns Alice's first ratchet public key only from her first message header. */
        fun initBob(sharedSecret: ByteArray, bobSignedPreKey: SignedPreKeyPair): RatchetSession = RatchetSession(
            selfRatchetPrivateKey = bobSignedPreKey.privateKey,
            selfRatchetPublicKey = bobSignedPreKey.publicKey,
            remoteRatchetPublicKey = null,
            rootKey = sharedSecret,
            sendingChainKey = null,
            receivingChainKey = null,
        )

        fun restore(state: RatchetSessionState): RatchetSession = RatchetSession(
            selfRatchetPrivateKey = state.selfRatchetPrivateKey,
            selfRatchetPublicKey = state.selfRatchetPublicKey,
            remoteRatchetPublicKey = state.remoteRatchetPublicKey,
            rootKey = state.rootKey,
            sendingChainKey = state.sendingChainKey,
            receivingChainKey = state.receivingChainKey,
            sendMessageNumber = state.sendMessageNumber,
            receiveMessageNumber = state.receiveMessageNumber,
            previousSendingChainLength = state.previousSendingChainLength,
            skippedMessageKeys = state.skippedMessageKeys.toMutableMap(),
        )
    }
}

data class RatchetSessionState(
    val selfRatchetPrivateKey: ByteArray,
    val selfRatchetPublicKey: ByteArray,
    val remoteRatchetPublicKey: ByteArray?,
    val rootKey: ByteArray,
    val sendingChainKey: ByteArray?,
    val receivingChainKey: ByteArray?,
    val sendMessageNumber: Int,
    val receiveMessageNumber: Int,
    val previousSendingChainLength: Int,
    val skippedMessageKeys: Map<String, ByteArray>,
)
