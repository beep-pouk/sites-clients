package com.securechat.app

import android.content.Context
import com.securechat.app.data.keystore.SecureKeyStorage
import com.securechat.app.data.local.AppDatabase
import com.securechat.app.data.remote.ApiClient
import com.securechat.app.data.remote.WebSocketClient
import com.securechat.app.data.repository.ChatRepository
import com.securechat.app.data.repository.ContactRepository
import com.securechat.app.data.repository.KeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Where the relay server lives. The emulator-friendly defaults point `10.0.2.2` at the host
 * machine's loopback interface, matching `server/README` instructions for running it locally;
 * point these at a real deployment's HTTPS/WSS origin for anything beyond local development.
 */
object ServerConfig {
    const val HTTP_BASE_URL = "http://10.0.2.2:8080/"
    const val WS_BASE_URL = "ws://10.0.2.2:8080"
}

/**
 * Hand-rolled dependency container (no DI framework, to keep the moving parts obvious) wiring
 * together secure local storage, the network layer, and the repositories that sit on top of the
 * `:crypto` module.
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = Json { ignoreUnknownKeys = true }

    val keyStorage = SecureKeyStorage(applicationContext)

    private val database by lazy { AppDatabase.create(applicationContext, keyStorage.getOrCreateDatabasePassphrase()) }

    private val serverApi = ApiClient.create(ServerConfig.HTTP_BASE_URL, json)

    val keyRepository by lazy { KeyRepository(keyStorage, serverApi) }

    val contactRepository by lazy { ContactRepository(database.contactDao()) }

    val chatRepository by lazy {
        ChatRepository(
            keyRepository = keyRepository,
            contactRepository = contactRepository,
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            serverApi = serverApi,
        )
    }

    val webSocketClient by lazy {
        WebSocketClient(
            client = ApiClient.webSocketHttpClient(),
            wsBaseUrl = ServerConfig.WS_BASE_URL,
            scope = applicationScope,
            onMessagePushed = {
                applicationScope.launch { runCatching { chatRepository.syncIncomingMessages() } }
            },
        )
    }
}
