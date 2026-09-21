package com.securechat.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val identitySigningKey: String, // base64
    val identityAgreementKey: String, // base64
    val addedAtMillis: Long,
    val verified: Boolean,
)

/** One row per conversation: the serialized Double Ratchet state for that peer. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val peerUserId: String,
    val stateJson: String,
    val localWasInitiator: Boolean,
    val updatedAtMillis: Long,
)

enum class MessageDirection { SENT, RECEIVED }

/**
 * A decrypted message, stored in plaintext form - safe only because the whole database file is
 * encrypted at rest by SQLCipher with a key that never leaves the Android Keystore. This mirrors
 * how mainstream E2E messengers persist their local message history.
 */
@Entity(tableName = "messages", indices = [Index("peerUserId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerUserId: String,
    val direction: MessageDirection,
    val body: String,
    val timestampMillis: Long,
    val serverMessageId: Long? = null,
)
