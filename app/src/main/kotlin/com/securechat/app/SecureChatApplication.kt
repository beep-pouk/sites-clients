package com.securechat.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class SecureChatApplication : Application(), DefaultLifecycleObserver {
    lateinit var container: AppContainer
        private set

    lateinit var lockController: AppLockController
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        container = AppContainer(this)
        // Nothing sensitive to protect before onboarding creates an identity; start unlocked so
        // a fresh install goes straight to onboarding instead of a confusing lock screen.
        lockController = AppLockController(startLocked = container.keyStorage.hasIdentity())
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Fires only when every activity has stopped (real backgrounding), never for a system dialog. */
    override fun onStop(owner: LifecycleOwner) {
        if (container.keyStorage.hasIdentity()) lockController.lock()
    }
}
