package com.securechat.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.AppContainer
import com.securechat.app.data.local.MessageEntity
import com.securechat.app.data.repository.Contact
import com.securechat.app.data.repository.IdentityKeyChangedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(private val container: AppContainer, private val peerUserId: String) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = container.chatRepository.observeConversation(peerUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    init {
        viewModelScope.launch { _contact.value = container.contactRepository.findContact(peerUserId) }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.chatRepository.sendMessage(peerUserId, trimmed) }
                .onFailure { error ->
                    _sendError.value = when (error) {
                        is IdentityKeyChangedException ->
                            "${contact.value?.displayName ?: peerUserId}'s security code changed. Verify it again before sending."
                        else -> error.message ?: "Failed to send message"
                    }
                }
        }
    }

    fun clearError() {
        _sendError.value = null
    }
}
