package com.securechat.app.data.repository

import com.securechat.app.data.local.ContactDao
import com.securechat.app.data.local.ContactEntity
import com.securechat.crypto.PublicIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Contact(
    val userId: String,
    val displayName: String,
    val identity: PublicIdentity,
    val addedAtMillis: Long,
    val verified: Boolean,
)

class ContactRepository(private val contactDao: ContactDao) {

    fun observeContacts(): Flow<List<Contact>> = contactDao.observeAll().map { list -> list.map { it.toContact() } }

    suspend fun findContact(userId: String): Contact? = contactDao.findById(userId)?.toContact()

    suspend fun addOrUpdateContact(userId: String, displayName: String, identity: PublicIdentity) {
        val existing = contactDao.findById(userId)
        contactDao.upsert(
            ContactEntity(
                userId = userId,
                displayName = displayName,
                identitySigningKey = encode(identity.signingPublicKey),
                identityAgreementKey = encode(identity.agreementPublicKey),
                addedAtMillis = existing?.addedAtMillis ?: System.currentTimeMillis(),
                verified = existing?.verified ?: false,
            ),
        )
    }

    /** Called when a message arrives from a userId with no saved contact card yet. */
    suspend fun addUnknownContactIfMissing(userId: String, identity: PublicIdentity) {
        if (contactDao.findById(userId) != null) return
        addOrUpdateContact(userId, displayName = "New contact (${userId.take(8)})", identity)
    }

    suspend fun markVerified(userId: String, verified: Boolean) = contactDao.setVerified(userId, verified)

    private fun ContactEntity.toContact() = Contact(
        userId = userId,
        displayName = displayName,
        identity = PublicIdentity(decode(identitySigningKey), decode(identityAgreementKey)),
        addedAtMillis = addedAtMillis,
        verified = verified,
    )
}
