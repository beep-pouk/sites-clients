package com.securechat.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securechat.app.AppContainer
import com.securechat.app.ui.chat.ChatListScreen
import com.securechat.app.ui.chat.ChatScreen
import com.securechat.app.ui.chat.SafetyNumberScreen
import com.securechat.app.ui.contacts.AddContactScreen
import com.securechat.app.ui.contacts.ContactsScreen
import com.securechat.app.ui.onboarding.OnboardingScreen
import com.securechat.app.ui.settings.SettingsScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val ADD_CONTACT = "add_contact"
    const val CHAT = "chat/{peerUserId}"
    const val SAFETY_NUMBER = "safety_number/{peerUserId}"

    fun chat(peerUserId: String) = "chat/$peerUserId"
    fun safetyNumber(peerUserId: String) = "safety_number/$peerUserId"
}

private data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

private val bottomDestinations = listOf(
    BottomDestination(Routes.CHATS, "Chats", Icons.Default.Chat),
    BottomDestination(Routes.CONTACTS, "Contacts", Icons.Default.Group),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
fun SecureChatNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val startDestination = if (container.keyStorage.hasIdentity()) Routes.CHATS else Routes.ONBOARDING

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(container = container) {
                    navController.navigate(Routes.CHATS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Routes.CHATS) {
                ChatListScreen(
                    container = container,
                    onOpenChat = { peerUserId -> navController.navigate(Routes.chat(peerUserId)) },
                    onAddContact = { navController.navigate(Routes.ADD_CONTACT) },
                )
            }
            composable(Routes.CONTACTS) {
                ContactsScreen(
                    container = container,
                    onAddContact = { navController.navigate(Routes.ADD_CONTACT) },
                    onOpenChat = { peerUserId -> navController.navigate(Routes.chat(peerUserId)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(container = container) }
            composable(Routes.ADD_CONTACT) {
                AddContactScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onContactAdded = { peerUserId ->
                        navController.navigate(Routes.chat(peerUserId)) {
                            popUpTo(Routes.CONTACTS)
                        }
                    },
                )
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument("peerUserId") { type = NavType.StringType }),
            ) { entry ->
                val peerUserId = entry.arguments?.getString("peerUserId") ?: return@composable
                ChatScreen(
                    container = container,
                    peerUserId = peerUserId,
                    onBack = { navController.popBackStack() },
                    onOpenSafetyNumber = { navController.navigate(Routes.safetyNumber(peerUserId)) },
                )
            }
            composable(
                route = Routes.SAFETY_NUMBER,
                arguments = listOf(navArgument("peerUserId") { type = NavType.StringType }),
            ) { entry ->
                val peerUserId = entry.arguments?.getString("peerUserId") ?: return@composable
                SafetyNumberScreen(container = container, peerUserId = peerUserId, onBack = { navController.popBackStack() })
            }
        }
    }
}
