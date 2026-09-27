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
 * Where the relay server lives. Defaults to the Android emulator's alias for the host machine's
 * loopback interface, matching `server/README` instructions for running it locally. A real
 * device can override this at runtime from Settings (persisted via [SecureKeyStorage]), so
 * pointing the app at a real deployment - e.g. the Render URL from `DEPLOY.md` - never requires
 * rebuilding the app, only restarting it so [AppContainer] re-reads the saved value.
 */
object ServerConfig {
    const val DEFAULT_HTTP_BASE_URL = "http://10.0.2.2:8080/"

    /** Derives the WebSocket origin (ws/wss) from a REST base URL (http/https), same host+port. */
    fun deriveWsBaseUrl(httpBaseUrl: String): String {
        val trimmed = httpBaseUrl.trimEnd('/')
        return when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
    }
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

    private val httpBaseUrl: String = keyStorage.loadServerBaseUrl()
        ?.let { it.trimEnd('/') + "/" }
        ?: ServerConfig.DEFAULT_HTTP_BASE_URL
    private val wsBaseUrl: String = ServerConfig.deriveWsBaseUrl(httpBaseUrl)

    private val database by lazy { AppDatabase.create(applicationContext, keyStorage.getOrCreateDatabasePassphrase()) }

    private val serverApi = ApiClient.create(httpBaseUrl, json)

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
            wsBaseUrl = wsBaseUrl,
            scope = applicationScope,
            onMessagePushed = {
                applicationScope.launch { runCatching { chatRepository.syncIncomingMessages() } }
            },
        )
    }
}
