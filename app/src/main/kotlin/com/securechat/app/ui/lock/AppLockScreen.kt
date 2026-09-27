package com.securechat.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Shown whenever [com.securechat.app.AppLockController] reports the app as locked (on cold start
 * for a returning user, and whenever the app comes back from the background). Falls through to
 * [onUnlock] immediately if the device has no biometric or PIN/pattern/password set up at all -
 * we can't demand a security measure the user's device doesn't offer, and this app's real
 * protection (message encryption, encrypted local storage) doesn't depend on this screen anyway;
 * it's an extra layer against someone picking up an already-unlocked phone.
 */
@Composable
fun AppLockScreen(activity: FragmentActivity, onUnlock: () -> Unit) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val currentOnUnlock by rememberUpdatedState(onUnlock)
    val context = LocalContext.current

    fun promptUnlock() {
        val biometricManager = BiometricManager.from(context)
        if (biometricManager.canAuthenticate(ALLOWED_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            currentOnUnlock()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SecureChat")
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    errorMessage = null
                    currentOnUnlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Errors like "user cancelled" are expected (they can retry with the button)
                    // and not worth alarming them with; only surface genuinely unexpected ones.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        errorMessage = errString.toString()
                    }
                }
            },
        )
        prompt.authenticate(promptInfo)
    }

    LaunchedEffect(Unit) { promptUnlock() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.height(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("SecureChat is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Unlock with your fingerprint, face, or device PIN to see your conversations.",
                style = MaterialTheme.typography.bodyMedium,
            )
            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { promptUnlock() }) { Text("Unlock") }
        }
    }
}
