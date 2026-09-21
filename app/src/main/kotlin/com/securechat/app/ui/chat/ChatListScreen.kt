package com.securechat.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securechat.app.AppContainer
import com.securechat.app.ui.rememberViewModelFactory
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(container: AppContainer, onOpenChat: (String) -> Unit, onAddContact: () -> Unit) {
    val viewModel: ChatListViewModel = viewModel(factory = rememberViewModelFactory { ChatListViewModel(container) })
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddContact) {
                Icon(Icons.Default.Add, contentDescription = "New contact")
            }
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No conversations yet. Add a contact to get started.")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(conversations, key = { it.peerUserId }) { conversation ->
                    ListItem(
                        headlineContent = { Text(conversation.displayName) },
                        supportingContent = { Text(conversation.lastMessage ?: "Say hello 👋") },
                        trailingContent = {
                            conversation.lastTimestamp?.let {
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
                            }
                        },
                        modifier = Modifier.clickable { onOpenChat(conversation.peerUserId) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
