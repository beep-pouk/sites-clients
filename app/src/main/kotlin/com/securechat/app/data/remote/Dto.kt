package com.securechat.app.data.remote

import kotlinx.serialization.Serializable

/**
 * Transport DTOs for the relay server's REST API. These mirror `server/.../Models.kt` exactly;
 * they're kept as an independent copy (rather than a shared module) since server and app are
 * different platforms/toolchains, and this is the server's only contract with any client.
 */
@Serializable
data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class OneTimePreKeyDto(val keyId: Int, val publicKey: String)

@Serializable
data class RegisterRequest(
    val userId: String,
    val identitySigningKey: String,
    val identityAgreementKey: String,
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
    /**
     * Ed25519 signature (base64) over identitySigningKey||identityAgreementKey||signedPreKey.publicKey,
     * made with this identity's own signing private key. The server requires this to match the
     * signing key it already has on file before allowing an update to an existing userId, so a
     * stranger who merely learns a userId can't hijack that identity by re-registering it.
     */
    val signature: String,
)

@Serializable
data class UploadOneTimePreKeysRequest(val oneTimePreKeys: List<OneTimePreKeyDto>)

@Serializable
data class PreKeyBundleResponse(
    val userId: String,
    val identitySigningKey: String,
    val identityAgreementKey: String,
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKey: OneTimePreKeyDto?,
)

@Serializable
data class SendMessageRequest(
    val recipientUserId: String,
    val senderUserId: String,
    val ciphertextEnvelope: String,
    val isHandshake: Boolean,
)

@Serializable
data class SendMessageResponse(val messageId: Long)

@Serializable
data class StoredMessageDto(
    val messageId: Long,
    val senderUserId: String,
    val ciphertextEnvelope: String,
    val isHandshake: Boolean,
    val timestamp: Long,
)

@Serializable
data class AckRequest(val messageIds: List<Long>)

/**
 * The end-to-end encrypted payload format, opaque to the server, carried inside
 * [SendMessageRequest.ciphertextEnvelope] as a JSON string. Only [RatchetMessageDto] contains
 * anything ciphertext-shaped; a [HandshakeEnvelopeDto] additionally carries the public X3DH
 * values Bob needs to derive the same shared secret Alice did.
 */
@Serializable
data class RatchetHeaderDto(val dhPublicKey: String, val previousChainLength: Int, val messageNumber: Int)

@Serializable
data class RatchetMessageDto(val header: RatchetHeaderDto, val ciphertext: String)

@Serializable
data class HandshakeEnvelopeDto(
    val initiatorUserId: String,
    val initiatorIdentitySigningKey: String,
    val initiatorIdentityAgreementKey: String,
    val initiatorEphemeralPublicKey: String,
    val usedOneTimePreKeyId: Int?,
    val firstMessage: RatchetMessageDto,
)
