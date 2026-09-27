package com.securechat.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistence for the relay server: registered users' public key material, unused one-time
 * prekeys, and queued (still-unacknowledged) encrypted message envelopes.
 *
 * Backed by SQLite for a self-contained, dependency-free MVP. The server only ever stores
 * public keys and opaque ciphertext - compromising this database does not expose any
 * conversation content or private key material, only metadata (who talked to whom, when).
 * A production deployment should swap this for a replicated database (e.g. Postgres) and a
 * connection pool; the single synchronized connection here is intentionally simple, not fast.
 */
class ServerStorage(databasePath: String) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath").also {
        it.createStatement().use { stmt -> stmt.execute("PRAGMA journal_mode=WAL") }
    }

    init {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    user_id TEXT PRIMARY KEY,
                    identity_signing_key TEXT NOT NULL,
                    identity_agreement_key TEXT NOT NULL,
                    signed_prekey_id INTEGER NOT NULL,
                    signed_prekey_public TEXT NOT NULL,
                    signed_prekey_signature TEXT NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS one_time_prekeys (
                    user_id TEXT NOT NULL,
                    key_id INTEGER NOT NULL,
                    public_key TEXT NOT NULL,
                    consumed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (user_id, key_id)
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    message_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    recipient_user_id TEXT NOT NULL,
                    sender_user_id TEXT NOT NULL,
                    ciphertext_envelope TEXT NOT NULL,
                    is_handshake INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient_user_id)")
        }
    }

    suspend fun registerUser(request: RegisterRequest) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                """
                INSERT INTO users (user_id, identity_signing_key, identity_agreement_key, signed_prekey_id, signed_prekey_public, signed_prekey_signature)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    identity_signing_key = excluded.identity_signing_key,
                    identity_agreement_key = excluded.identity_agreement_key,
                    signed_prekey_id = excluded.signed_prekey_id,
                    signed_prekey_public = excluded.signed_prekey_public,
                    signed_prekey_signature = excluded.signed_prekey_signature
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, request.userId)
                ps.setString(2, request.identitySigningKey)
                ps.setString(3, request.identityAgreementKey)
                ps.setInt(4, request.signedPreKey.keyId)
                ps.setString(5, request.signedPreKey.publicKey)
                ps.setString(6, request.signedPreKey.signature)
                ps.executeUpdate()
            }
            insertOneTimePreKeysLocked(request.userId, request.oneTimePreKeys)
        }
    }

    suspend fun addOneTimePreKeys(userId: String, keys: List<OneTimePreKeyDto>) = withContext(Dispatchers.IO) {
        synchronized(connection) { insertOneTimePreKeysLocked(userId, keys) }
    }

    private fun insertOneTimePreKeysLocked(userId: String, keys: List<OneTimePreKeyDto>) {
        connection.prepareStatement(
            "INSERT OR IGNORE INTO one_time_prekeys (user_id, key_id, public_key, consumed) VALUES (?, ?, ?, 0)",
        ).use { ps ->
            for (key in keys) {
                ps.setString(1, userId)
                ps.setInt(2, key.keyId)
                ps.setString(3, key.publicKey)
                ps.addBatch()
            }
            if (keys.isNotEmpty()) ps.executeBatch()
        }
    }

    /** Fetches a bundle for starting a session with [userId], consuming one one-time prekey if available. */
    suspend fun fetchBundle(userId: String): PreKeyBundleResponse? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            val user = connection.prepareStatement(
                "SELECT identity_signing_key, identity_agreement_key, signed_prekey_id, signed_prekey_public, signed_prekey_signature FROM users WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext null
                    UserRow(
                        identitySigningKey = rs.getString(1),
                        identityAgreementKey = rs.getString(2),
                        signedPreKeyId = rs.getInt(3),
                        signedPreKeyPublic = rs.getString(4),
                        signedPreKeySignature = rs.getString(5),
                    )
                }
            }

            val oneTimePreKey = connection.prepareStatement(
                "SELECT key_id, public_key FROM one_time_prekeys WHERE user_id = ? AND consumed = 0 LIMIT 1",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) OneTimePreKeyDto(rs.getInt(1), rs.getString(2)) else null
                }
            }

            if (oneTimePreKey != null) {
                connection.prepareStatement("UPDATE one_time_prekeys SET consumed = 1 WHERE user_id = ? AND key_id = ?").use { ps ->
                    ps.setString(1, userId)
                    ps.setInt(2, oneTimePreKey.keyId)
                    ps.executeUpdate()
                }
            }

            PreKeyBundleResponse(
                userId = userId,
                identitySigningKey = user.identitySigningKey,
                identityAgreementKey = user.identityAgreementKey,
                signedPreKey = SignedPreKeyDto(user.signedPreKeyId, user.signedPreKeyPublic, user.signedPreKeySignature),
                oneTimePreKey = oneTimePreKey,
            )
        }
    }

    fun userExists(userId: String): Boolean = synchronized(connection) {
        connection.prepareStatement("SELECT 1 FROM users WHERE user_id = ?").use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { it.next() }
        }
    }

    /** The identity signing key currently on file for [userId], if registered - used to verify
     *  that a re-registration is signed by the same identity, not a hijack attempt. */
    fun getIdentitySigningKey(userId: String): String? = synchronized(connection) {
        connection.prepareStatement("SELECT identity_signing_key FROM users WHERE user_id = ?").use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    suspend fun storeMessage(request: SendMessageRequest): StoredMessageDto = withContext(Dispatchers.IO) {
        synchronized(connection) {
            val now = System.currentTimeMillis()
            connection.prepareStatement(
                "INSERT INTO messages (recipient_user_id, sender_user_id, ciphertext_envelope, is_handshake, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setString(1, request.recipientUserId)
                ps.setString(2, request.senderUserId)
                ps.setString(3, request.ciphertextEnvelope)
                ps.setInt(4, if (request.isHandshake) 1 else 0)
                ps.setLong(5, now)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    keys.next()
                    val id = keys.getLong(1)
                    StoredMessageDto(id, request.senderUserId, request.ciphertextEnvelope, request.isHandshake, now)
                }
            }
        }
    }

    suspend fun countQueuedMessages(recipientUserId: String): Int = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT COUNT(*) FROM messages WHERE recipient_user_id = ?").use { ps ->
                ps.setString(1, recipientUserId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
    }

    suspend fun fetchMessages(userId: String): List<StoredMessageDto> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT message_id, sender_user_id, ciphertext_envelope, is_handshake, created_at FROM messages WHERE recipient_user_id = ? ORDER BY message_id ASC",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<StoredMessageDto>()
                    while (rs.next()) {
                        results += StoredMessageDto(
                            messageId = rs.getLong(1),
                            senderUserId = rs.getString(2),
                            ciphertextEnvelope = rs.getString(3),
                            isHandshake = rs.getInt(4) == 1,
                            timestamp = rs.getLong(5),
                        )
                    }
                    results
                }
            }
        }
    }

    suspend fun acknowledgeMessages(userId: String, messageIds: List<Long>) = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext
        synchronized(connection) {
            val placeholders = messageIds.joinToString(",") { "?" }
            connection.prepareStatement(
                "DELETE FROM messages WHERE recipient_user_id = ? AND message_id IN ($placeholders)",
            ).use { ps ->
                ps.setString(1, userId)
                messageIds.forEachIndexed { index, id -> ps.setLong(index + 2, id) }
                ps.executeUpdate()
            }
        }
    }

    fun close() = connection.close()

    private data class UserRow(
        val identitySigningKey: String,
        val identityAgreementKey: String,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: String,
        val signedPreKeySignature: String,
    )
}
