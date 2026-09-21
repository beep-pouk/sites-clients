package com.securechat.crypto

/**
 * Human-comparable "safety number" derived from both parties' identity keys, for out-of-band
 * verification (reading digits aloud, or comparing a scanned QR code) that a conversation has
 * not been relayed through an attacker impersonating one of the participants.
 */
object Fingerprint {
    private const val ITERATIONS = 5200
    private const val TOTAL_DIGITS = 60

    fun forIdentity(userId: String, identity: PublicIdentity): String {
        var material = identity.agreementPublicKey + identity.signingPublicKey + userId.toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) { material = CryptoPrimitives.sha256(material) }
        return toDigits(material)
    }

    /** Both participants compute this the same way regardless of who's "local", by fixing an order. */
    fun forConversation(
        localUserId: String,
        localIdentity: PublicIdentity,
        remoteUserId: String,
        remoteIdentity: PublicIdentity,
    ): String = if (localUserId <= remoteUserId) {
        forIdentity(localUserId, localIdentity) + forIdentity(remoteUserId, remoteIdentity)
    } else {
        forIdentity(remoteUserId, remoteIdentity) + forIdentity(localUserId, localIdentity)
    }

    private fun toDigits(seed: ByteArray): String {
        val sb = StringBuilder()
        var material = seed
        while (sb.length < TOTAL_DIGITS) {
            material = CryptoPrimitives.sha256(material)
            var i = 0
            while (i + 4 <= material.size && sb.length < TOTAL_DIGITS) {
                val word = ((material[i].toLong() and 0xFF) shl 24) or
                    ((material[i + 1].toLong() and 0xFF) shl 16) or
                    ((material[i + 2].toLong() and 0xFF) shl 8) or
                    (material[i + 3].toLong() and 0xFF)
                sb.append((word % 100000L).toString().padStart(5, '0'))
                i += 4
            }
        }
        return sb.toString()
    }
}
