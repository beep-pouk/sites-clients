package com.securechat.server

import io.ktor.server.plugins.BadRequestException

/**
 * Input bounds enforced before anything touches storage or the crypto layer. None of these are
 * about correctness of the crypto (the client's signatures/AEAD tags already guarantee that) -
 * they exist purely to stop a client (malicious or buggy) from writing unbounded data into the
 * database or forcing unbounded work per request, since a relay that only forwards opaque bytes
 * has no other way to tell a legitimate payload from an abusive one.
 */
private const val MAX_USER_ID_LENGTH = 64
private val USER_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,$MAX_USER_ID_LENGTH}$")

// Generous bound for a single base64-encoded key/signature field (raw Ed25519/X25519 material is
// 32-64 bytes, ~90 chars base64 at most) - large enough for any real key, far too small to abuse.
private const val MAX_ENCODED_KEY_LENGTH = 256

private const val MAX_ONE_TIME_PREKEYS_PER_REQUEST = 100

// ~150 KB decoded: comfortably fits an X3DH handshake envelope or a long text message, nowhere
// near enough to let a single request exhaust server memory or disk.
private const val MAX_CIPHERTEXT_ENVELOPE_LENGTH = 200_000

const val MAX_QUEUED_MESSAGES_PER_RECIPIENT = 500

private fun requireValid(condition: Boolean, message: String) {
    if (!condition) throw BadRequestException(message)
}

fun validateUserId(userId: String) = requireValid(USER_ID_PATTERN.matches(userId), "invalid userId")

private fun validateEncodedField(value: String) =
    requireValid(value.isNotEmpty() && value.length <= MAX_ENCODED_KEY_LENGTH, "invalid key or signature field")

fun validateOneTimePreKeys(keys: List<OneTimePreKeyDto>) {
    requireValid(keys.size <= MAX_ONE_TIME_PREKEYS_PER_REQUEST, "too many one-time prekeys in a single request")
    keys.forEach { validateEncodedField(it.publicKey) }
}

fun RegisterRequest.validate() {
    validateUserId(userId)
    validateEncodedField(identitySigningKey)
    validateEncodedField(identityAgreementKey)
    validateEncodedField(signedPreKey.publicKey)
    validateEncodedField(signedPreKey.signature)
    validateEncodedField(signature)
    validateOneTimePreKeys(oneTimePreKeys)
}

fun SendMessageRequest.validate() {
    validateUserId(recipientUserId)
    validateUserId(senderUserId)
    requireValid(
        ciphertextEnvelope.isNotEmpty() && ciphertextEnvelope.length <= MAX_CIPHERTEXT_ENVELOPE_LENGTH,
        "ciphertext envelope too large",
    )
}
