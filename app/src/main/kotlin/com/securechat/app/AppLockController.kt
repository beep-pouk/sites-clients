package com.securechat.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tracks whether the app's content is currently hidden behind a biometric/device-credential
 * prompt. Backed by [androidx.lifecycle.ProcessLifecycleOwner] in [SecureChatApplication] so a
 * transient system dialog (like the biometric prompt itself, or a permission dialog) pausing the
 * activity doesn't re-lock the app - only the whole process actually leaving the foreground does.
 */
class AppLockController(startLocked: Boolean) {
    var isLocked by mutableStateOf(startLocked)
        private set

    fun lock() {
        isLocked = true
    }

    fun unlock() {
        isLocked = false
    }
}
