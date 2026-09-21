package com.securechat.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.securechat.app.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    var displayName by remember { mutableStateOf(container.keyStorage.loadDisplayName() ?: "") }
    var saved by remember { mutableStateOf(false) }
    val userId = remember { container.keyRepository.localUserId }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
        ) {
            Text("Display name", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    saved = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    container.keyStorage.saveDisplayName(displayName.trim())
                    saved = true
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Save") }
            if (saved) Text("Saved", style = MaterialTheme.typography.labelSmall)

            Spacer(modifier = Modifier.height(24.dp))
            Text("Your ID", style = MaterialTheme.typography.titleMedium)
            Text(userId, style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Your private keys are generated and stored only on this device, protected " +
                    "by the Android Keystore. They are never sent anywhere - only your public keys are.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
