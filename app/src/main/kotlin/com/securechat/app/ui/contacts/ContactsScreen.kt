package com.securechat.app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Verified
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
import com.securechat.app.data.repository.Contact
import com.securechat.app.ui.rememberViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(container: AppContainer, onAddContact: () -> Unit, onOpenChat: (String) -> Unit) {
    val viewModel: ContactsViewModel = viewModel(factory = rememberViewModelFactory { ContactsViewModel(container) })
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Contacts") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddContact) {
                Icon(Icons.Default.Add, contentDescription = "Add contact")
            }
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No contacts yet. Tap + to scan someone's QR code.")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(contacts, key = { it.userId }) { contact ->
                    ContactRow(contact = contact, onClick = { onOpenChat(contact.userId) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(contact.displayName) },
        supportingContent = { Text(if (contact.verified) "Verified" else "Not verified") },
        trailingContent = {
            if (contact.verified) Icon(Icons.Default.Verified, contentDescription = "Verified")
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
