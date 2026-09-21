package com.securechat.app.qr

import kotlinx.serialization.Serializable

/**
 * What a QR code encodes for an in-person contact exchange: enough to add someone as a verified
 * contact without ever touching the relay server, which is the strongest way to defeat a
 * malicious or compromised server trying to substitute its own keys (a "TOFU" pinning step).
 */
@Serializable
data class ContactCardPayload(
    val userId: String,
    val displayName: String,
    val identitySigningKey: String,
    val identityAgreementKey: String,
)
