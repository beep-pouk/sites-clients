package com.securechat.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.securechat.app.ui.lock.AppLockScreen
import com.securechat.app.ui.navigation.SecureChatNavGraph
import com.securechat.app.ui.theme.SecureChatTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps message content out of screenshots, screen recordings, the OS screenshot-on-crash
        // report, and the recent-apps switcher thumbnail - standard practice for a messenger
        // whose whole purpose is that message content stays private.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val app = application as SecureChatApplication
        val container = app.container

        setContent {
            SecureChatTheme {
                if (app.lockController.isLocked) {
                    AppLockScreen(activity = this, onUnlock = { app.lockController.unlock() })
                } else {
                    SecureChatNavGraph(container = container)
                }
            }
        }
    }
}
