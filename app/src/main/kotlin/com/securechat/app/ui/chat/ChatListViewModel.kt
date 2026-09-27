package com.securechat.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationSummary(
    val peerUserId: String,
    val displayName: String,
    val verified: Boolean,
    val lastMessage: String?,
    val lastTimestamp: Long?,
)

/**
 * Backs the chat list ("Chats" tab). Also owns keeping the socket connected and polling for new
 * messages while the list is visible - a simple stand-in for the push-notification wake-up a
 * production build would use (see README "Known limitations").
 */
class ChatListViewModel(private val container: AppContainer) : ViewModel() {

    val conversations: StateFlow<List<ConversationSummary>> = combine(
        container.contactRepository.observeContacts(),
        container.chatRepository.observeConversationSummaries(),
    ) { contacts, summaries ->
        val summaryByPeer = summaries.associateBy { it.peerUserId }
        contacts
            .map { contact ->
                val summary = summaryByPeer[contact.userId]
                ConversationSummary(
                    peerUserId = contact.userId,
                    displayName = contact.displayName,
                    verified = contact.verified,
                    lastMessage = summary?.lastBody,
                    lastTimestamp = summary?.lastTimestamp,
                )
            }
            .sortedByDescending { it.lastTimestamp ?: -1 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        container.webSocketClient.connect(container.keyRepository.localUserId, container.keyRepository.loadIdentity())
        viewModelScope.launch {
            while (true) {
                runCatching { container.chatRepository.syncIncomingMessages() }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        container.webSocketClient.disconnect()
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 10_000L
    }
}
