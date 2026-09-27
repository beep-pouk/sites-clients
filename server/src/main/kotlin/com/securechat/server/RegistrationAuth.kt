package com.securechat.server

import com.securechat.crypto.CryptoPrimitives
import com.securechat.crypto.RegistrationProof
import java.util.Base64

/**
 * Guards against identity takeover: without this, `/v1/register` would let anyone silently
 * overwrite an already-registered user's published identity key just by POSTing that user's
 * userId again, redirecting every future first-contact conversation with them to an attacker.
 *
 * Registering a brand-new userId needs no proof of anything (consistent with the rest of the
 * app's trust-on-first-use model - X3DH's own signature check on the signed prekey already
 * ensures nobody can publish a *usable* bundle without the matching private signing key). But an
 * update to an *existing* userId must be signed by that identity's already-on-file signing key
 * (see [RegistrationProof]), proving the caller is the same party that registered it.
 */
object RegistrationAuth {
    /** True if [request]'s signature verifies against [existingSigningKeyBase64] (the key on file). */
    fun verifiesAgainstExistingIdentity(request: RegisterRequest, existingSigningKeyBase64: String): Boolean = try {
        // The signed message is whatever the request claims *now* (so a legitimate key rotation,
        // signed by the outgoing key, would also verify) - only the verification key itself must
        // be the one already on file, proving the caller controls the existing identity.
        val verificationKey = Base64.getDecoder().decode(existingSigningKeyBase64)
        val message = RegistrationProof.message(
            identitySigningKey = Base64.getDecoder().decode(request.identitySigningKey),
            identityAgreementKey = Base64.getDecoder().decode(request.identityAgreementKey),
            signedPreKeyPublic = Base64.getDecoder().decode(request.signedPreKey.publicKey),
        )
        CryptoPrimitives.verify(verificationKey, message, Base64.getDecoder().decode(request.signature))
    } catch (e: IllegalArgumentException) {
        false // malformed base64 anywhere in the request
    }
}
