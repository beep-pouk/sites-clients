package com.securechat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE userId = :userId")
    suspend fun findById(userId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET verified = :verified WHERE userId = :userId")
    suspend fun setVerified(userId: String, verified: Boolean)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE peerUserId = :peerUserId")
    suspend fun find(peerUserId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)
}

data class PeerLastMessage(val peerUserId: String, val lastTimestamp: Long, val lastBody: String)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerUserId = :peerUserId ORDER BY timestampMillis ASC, id ASC")
    fun observeConversation(peerUserId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.peerUserId AS peerUserId, m.timestampMillis AS lastTimestamp, m.body AS lastBody
        FROM messages m
        INNER JOIN (
            SELECT peerUserId, MAX(id) AS maxId FROM messages GROUP BY peerUserId
        ) latest ON m.peerUserId = latest.peerUserId AND m.id = latest.maxId
        ORDER BY m.timestampMillis DESC
        """,
    )
    fun observeLastMessagePerConversation(): Flow<List<PeerLastMessage>>

    @Insert
    suspend fun insert(message: MessageEntity): Long
}
