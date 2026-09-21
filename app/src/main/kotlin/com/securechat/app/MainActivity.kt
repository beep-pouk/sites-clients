package com.securechat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.securechat.app.ui.navigation.SecureChatNavGraph
import com.securechat.app.ui.theme.SecureChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SecureChatApplication).container
        setContent {
            SecureChatTheme {
                SecureChatNavGraph(container = container)
            }
        }
    }
}
