package com.securechat.app.data.keystore

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.securechat.crypto.CryptoPrimitives
import com.securechat.crypto.IdentityKeyPair
import com.securechat.crypto.OneTimePreKeyPair
import com.securechat.crypto.SignedPreKeyPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Stores the device's long-term private key material (identity key, current signed prekey,
 * unused one-time prekeys) and the local SQLCipher database passphrase.
 *
 * Everything here is encrypted at rest via Jetpack Security's [EncryptedSharedPreferences],
 * whose master key lives non-exportably in the Android Keystore (backed by hardware on most
 * devices). None of this material ever leaves the device or is sent to the relay server - only
 * the corresponding *public* keys are ever published there.
 */
class SecureKeyStorage(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "securechat_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hasIdentity(): Boolean = prefs.contains(KEY_IDENTITY)

    fun saveDisplayName(name: String) = prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()

    fun loadDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    fun getOrCreateLocalUserId(): String {
        prefs.getString(KEY_LOCAL_USER_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_LOCAL_USER_ID, id).apply()
        return id
    }

    fun getOrCreateDatabasePassphrase(): ByteArray {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return decode(it) }
        val passphrase = CryptoPrimitives.randomBytes(32)
        prefs.edit().putString(KEY_DB_PASSPHRASE, encode(passphrase)).apply()
        return passphrase
    }

    fun saveIdentity(identity: IdentityKeyPair) {
        val dto = IdentityDto(
            signingPublicKey = encode(identity.signingPublicKey),
            signingPrivateKey = encode(identity.signingPrivateKey),
            agreementPublicKey = encode(identity.agreementPublicKey),
            agreementPrivateKey = encode(identity.agreementPrivateKey),
        )
        prefs.edit().putString(KEY_IDENTITY, json.encodeToString(dto)).apply()
    }

    fun loadIdentity(): IdentityKeyPair? {
        val raw = prefs.getString(KEY_IDENTITY, null) ?: return null
        val dto = json.decodeFromString<IdentityDto>(raw)
        return IdentityKeyPair.restore(
            signingPublicKey = decode(dto.signingPublicKey),
            signingPrivateKey = decode(dto.signingPrivateKey),
            agreementPublicKey = decode(dto.agreementPublicKey),
            agreementPrivateKey = decode(dto.agreementPrivateKey),
        )
    }

    fun saveSignedPreKey(signedPreKey: SignedPreKeyPair) {
        val dto = SignedPreKeyDto(
            keyId = signedPreKey.keyId,
            publicKey = encode(signedPreKey.publicKey),
            privateKey = encode(signedPreKey.privateKey),
            signature = encode(signedPreKey.signature),
        )
        prefs.edit().putString(KEY_SIGNED_PREKEY, json.encodeToString(dto)).apply()
    }

    fun loadSignedPreKey(): SignedPreKeyPair? {
        val raw = prefs.getString(KEY_SIGNED_PREKEY, null) ?: return null
        val dto = json.decodeFromString<SignedPreKeyDto>(raw)
        return SignedPreKeyPair.restore(dto.keyId, decode(dto.publicKey), decode(dto.privateKey), decode(dto.signature))
    }

    fun saveOneTimePreKeys(keys: List<OneTimePreKeyPair>) {
        val dtos = keys.map { OneTimePreKeyDto(it.keyId, encode(it.publicKey), encode(it.privateKey)) }
        prefs.edit().putString(KEY_ONE_TIME_PREKEYS, json.encodeToString(dtos)).apply()
    }

    fun loadOneTimePreKeys(): List<OneTimePreKeyPair> {
        val raw = prefs.getString(KEY_ONE_TIME_PREKEYS, null) ?: return emptyList()
        val dtos = json.decodeFromString<List<OneTimePreKeyDto>>(raw)
        return dtos.map { OneTimePreKeyPair.restore(it.keyId, decode(it.publicKey), decode(it.privateKey)) }
    }

    /** Removes a one-time prekey once the server reports it was consumed by an initiator. */
    fun removeOneTimePreKey(keyId: Int) {
        saveOneTimePreKeys(loadOneTimePreKeys().filterNot { it.keyId == keyId })
    }

    fun nextOneTimePreKeyId(): Int = (loadOneTimePreKeys().maxOfOrNull { it.keyId } ?: 0) + 1

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    @Serializable
    private data class IdentityDto(
        val signingPublicKey: String,
        val signingPrivateKey: String,
        val agreementPublicKey: String,
        val agreementPrivateKey: String,
    )

    @Serializable
    private data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val privateKey: String, val signature: String)

    @Serializable
    private data class OneTimePreKeyDto(val keyId: Int, val publicKey: String, val privateKey: String)

    companion object {
        private const val KEY_LOCAL_USER_ID = "local_user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_IDENTITY = "identity"
        private const val KEY_SIGNED_PREKEY = "signed_prekey"
        private const val KEY_ONE_TIME_PREKEYS = "one_time_prekeys"
    }
}
