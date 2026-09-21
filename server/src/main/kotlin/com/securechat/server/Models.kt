package com.securechat.server

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the relay server. Every key and ciphertext is transported as base64 text; the
 * server never decodes [SendMessageRequest.ciphertextEnvelope] beyond storing/forwarding it -
 * it is produced and consumed entirely by the Android app's crypto layer.
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

@Serializable
data class ErrorResponse(val error: String)
