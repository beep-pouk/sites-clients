package com.securechat.crypto

/**
 * Proves that whoever is calling one of the relay server's owner-only endpoints for a given
 * userId (fetching/acking their queued messages, topping up their one-time prekeys, or opening
 * their push-notification WebSocket) actually owns that identity, rather than merely knowing (or
 * guessing) its userId. Without this, anyone who learned a userId could read or silently delete
 * messages queued for them, pollute their prekey pool, or eavesdrop on their delivery metadata.
 *
 * The timestamp is included and bounded (see [MAX_CLOCK_SKEW_MILLIS]) so a captured signature
 * can't be replayed indefinitely to keep re-issuing these calls.
 */
object UserIdOwnershipProof {
    private val INFO = "SecureChat-UserIdOwnership-v1".toByteArray(Charsets.UTF_8)
    const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L

    fun message(userId: String, timestampMillis: Long): ByteArray =
        INFO + userId.toByteArray(Charsets.UTF_8) + timestampMillis.toString().toByteArray(Charsets.UTF_8)

    fun sign(identity: IdentityKeyPair, userId: String, timestampMillis: Long): ByteArray =
        CryptoPrimitives.sign(identity.signingPrivateKey, message(userId, timestampMillis))

    fun verify(identitySigningKey: ByteArray, userId: String, timestampMillis: Long, signature: ByteArray, now: Long): Boolean {
        if (kotlin.math.abs(now - timestampMillis) > MAX_CLOCK_SKEW_MILLIS) return false
        return CryptoPrimitives.verify(identitySigningKey, message(userId, timestampMillis), signature)
    }
}
