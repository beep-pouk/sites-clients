package com.securechat.app.data.repository

import com.securechat.app.data.keystore.SecureKeyStorage
import com.securechat.app.data.remote.OneTimePreKeyDto
import com.securechat.app.data.remote.RegisterRequest
import com.securechat.app.data.remote.ServerApi
import com.securechat.app.data.remote.SignedPreKeyDto
import com.securechat.app.data.remote.UploadOneTimePreKeysRequest
import com.securechat.app.data.remote.requireSuccessful
import com.securechat.crypto.IdentityKeyPair
import com.securechat.crypto.OneTimePreKeyPair
import com.securechat.crypto.OneTimePreKeyPublic
import com.securechat.crypto.PreKeyBundle
import com.securechat.crypto.PublicIdentity
import com.securechat.crypto.SignedPreKeyPair
import com.securechat.crypto.SignedPreKeyPublic
import java.util.Base64

/**
 * Owns the local device identity: generating it on first run, publishing a signed prekey and a
 * pool of one-time prekeys to the relay server, and replenishing one-time prekeys as contacts
 * consume them to start new sessions. Private key material never leaves [SecureKeyStorage].
 */
class KeyRepository(private val keyStorage: SecureKeyStorage, private val serverApi: ServerApi) {

    val localUserId: String get() = keyStorage.getOrCreateLocalUserId()

    suspend fun ensureIdentityAndRegister(): IdentityKeyPair {
        val identity = keyStorage.loadIdentity() ?: IdentityKeyPair.generate().also { keyStorage.saveIdentity(it) }
        val signedPreKey = keyStorage.loadSignedPreKey()
            ?: SignedPreKeyPair.generate(identity, keyId = 1).also { keyStorage.saveSignedPreKey(it) }
        var oneTimePreKeys = keyStorage.loadOneTimePreKeys()
        if (oneTimePreKeys.isEmpty()) {
            oneTimePreKeys = OneTimePreKeyPair.generateBatch(startId = 1, count = ONE_TIME_PREKEY_BATCH_SIZE)
            keyStorage.saveOneTimePreKeys(oneTimePreKeys)
        }

        serverApi.register(
            RegisterRequest(
                userId = localUserId,
                identitySigningKey = encode(identity.signingPublicKey),
                identityAgreementKey = encode(identity.agreementPublicKey),
                signedPreKey = SignedPreKeyDto(signedPreKey.keyId, encode(signedPreKey.publicKey), encode(signedPreKey.signature)),
                oneTimePreKeys = oneTimePreKeys.map { OneTimePreKeyDto(it.keyId, encode(it.publicKey)) },
            ),
        ).requireSuccessful("register")
        return identity
    }

    fun loadIdentity(): IdentityKeyPair =
        keyStorage.loadIdentity() ?: error("Identity not initialized; call ensureIdentityAndRegister() first")

    fun loadPublicIdentity(): PublicIdentity = loadIdentity().publicIdentity()

    fun loadSignedPreKey(): SignedPreKeyPair = keyStorage.loadSignedPreKey() ?: error("Signed prekey not initialized")

    /** Looks up and removes a locally-held one-time prekey a peer's handshake says it used. */
    fun consumeOneTimePreKey(keyId: Int?): OneTimePreKeyPair? {
        if (keyId == null) return null
        val key = keyStorage.loadOneTimePreKeys().find { it.keyId == keyId } ?: return null
        keyStorage.removeOneTimePreKey(keyId)
        return key
    }

    suspend fun replenishOneTimePreKeysIfLow() {
        val remaining = keyStorage.loadOneTimePreKeys()
        if (remaining.size > ONE_TIME_PREKEY_REPLENISH_THRESHOLD) return
        val startId = keyStorage.nextOneTimePreKeyId()
        val fresh = OneTimePreKeyPair.generateBatch(startId, ONE_TIME_PREKEY_BATCH_SIZE)
        keyStorage.saveOneTimePreKeys(remaining + fresh)
        serverApi.uploadOneTimePreKeys(
            localUserId,
            UploadOneTimePreKeysRequest(fresh.map { OneTimePreKeyDto(it.keyId, encode(it.publicKey)) }),
        ).requireSuccessful("upload one-time prekeys")
    }

    suspend fun fetchBundleFor(userId: String): PreKeyBundle {
        val response = serverApi.fetchBundle(userId)
        val body = response.body() ?: error("No prekey bundle for $userId (HTTP ${response.code()})")
        return PreKeyBundle(
            userId = body.userId,
            identity = PublicIdentity(decode(body.identitySigningKey), decode(body.identityAgreementKey)),
            signedPreKey = SignedPreKeyPublic(body.signedPreKey.keyId, decode(body.signedPreKey.publicKey), decode(body.signedPreKey.signature)),
            oneTimePreKey = body.oneTimePreKey?.let { OneTimePreKeyPublic(it.keyId, decode(it.publicKey)) },
        )
    }

    companion object {
        private const val ONE_TIME_PREKEY_BATCH_SIZE = 20
        private const val ONE_TIME_PREKEY_REPLENISH_THRESHOLD = 5
    }
}

internal fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
