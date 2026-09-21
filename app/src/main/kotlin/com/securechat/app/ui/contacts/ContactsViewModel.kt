package com.securechat.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.AppContainer
import com.securechat.app.data.repository.Contact
import com.securechat.app.qr.ContactCardPayload
import com.securechat.crypto.PublicIdentity
import java.util.Base64
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ContactsViewModel(private val container: AppContainer) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    val contacts: StateFlow<List<Contact>> = container.contactRepository.observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val myContactCardQrContent: String
        get() {
            val identity = container.keyRepository.loadPublicIdentity()
            val payload = ContactCardPayload(
                userId = container.keyRepository.localUserId,
                displayName = container.keyStorage.loadDisplayName() ?: "Me",
                identitySigningKey = Base64.getEncoder().encodeToString(identity.signingPublicKey),
                identityAgreementKey = Base64.getEncoder().encodeToString(identity.agreementPublicKey),
            )
            return json.encodeToString(payload)
        }

    /** Adds (and immediately marks verified) a contact scanned via QR. Reports the new userId. */
    fun addContactFromQr(rawContent: String, onResult: (Result<String>) -> Unit) {
        val payload = runCatching { json.decodeFromString<ContactCardPayload>(rawContent) }.getOrNull()
        if (payload == null) {
            onResult(Result.failure(IllegalArgumentException("That QR code isn't a SecureChat contact card")))
            return
        }
        if (payload.userId == container.keyRepository.localUserId) {
            onResult(Result.failure(IllegalArgumentException("That's your own contact card")))
            return
        }
        viewModelScope.launch {
            container.contactRepository.addOrUpdateContact(
                userId = payload.userId,
                displayName = payload.displayName,
                identity = PublicIdentity(
                    signingPublicKey = Base64.getDecoder().decode(payload.identitySigningKey),
                    agreementPublicKey = Base64.getDecoder().decode(payload.identityAgreementKey),
                ),
            )
            // A QR exchange is an out-of-band, in-person check of the identity key itself.
            container.contactRepository.markVerified(payload.userId, verified = true)
            onResult(Result.success(payload.userId))
        }
    }
}
