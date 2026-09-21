package com.securechat.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.securechat.app.AppContainer
import com.securechat.crypto.Fingerprint
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberScreen(container: AppContainer, peerUserId: String, onBack: () -> Unit) {
    var displayName by remember { mutableStateOf(peerUserId) }
    var verified by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerUserId) {
        val contact = container.contactRepository.findContact(peerUserId) ?: return@LaunchedEffect
        displayName = contact.displayName
        verified = contact.verified
        fingerprint = Fingerprint.forConversation(
            localUserId = container.keyRepository.localUserId,
            localIdentity = container.keyRepository.loadPublicIdentity(),
            remoteUserId = peerUserId,
            remoteIdentity = contact.identity,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify $displayName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
        ) {
            Text(
                text = "Compare this number with $displayName in person or over a channel you both " +
                    "trust. If it matches on both devices, no one is intercepting this conversation.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(20.dp))

            val fp = fingerprint
            if (fp == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = fp.chunked(5).joinToString("  "),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = verified,
                    onCheckedChange = { checked ->
                        verified = checked
                        scope.launch { container.contactRepository.markVerified(peerUserId, checked) }
                    },
                )
                Text("I've verified this safety number")
            }
        }
    }
}
