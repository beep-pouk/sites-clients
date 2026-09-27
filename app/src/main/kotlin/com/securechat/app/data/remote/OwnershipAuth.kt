package com.securechat.app.data.remote

import com.securechat.crypto.IdentityKeyPair
import com.securechat.crypto.UserIdOwnershipProof
import java.util.Base64

/** Computes the `ts`/`sig` query params the server's owner-only endpoints require. */
object OwnershipAuth {
    data class Params(val timestamp: Long, val signature: String)

    fun current(identity: IdentityKeyPair, userId: String): Params {
        val timestamp = System.currentTimeMillis()
        val signature = UserIdOwnershipProof.sign(identity, userId, timestamp)
        return Params(timestamp, Base64.getEncoder().encodeToString(signature))
    }
}
