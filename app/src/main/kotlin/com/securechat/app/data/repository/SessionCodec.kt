package com.securechat.app.data.repository

import com.securechat.crypto.RatchetSessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON mirror of [RatchetSessionState] so it can be persisted as a single TEXT column. */
@Serializable
private data class RatchetSessionStateDto(
    val selfRatchetPrivateKey: String,
    val selfRatchetPublicKey: String,
    val remoteRatchetPublicKey: String?,
    val rootKey: String,
    val sendingChainKey: String?,
    val receivingChainKey: String?,
    val sendMessageNumber: Int,
    val receiveMessageNumber: Int,
    val previousSendingChainLength: Int,
    val skippedMessageKeys: Map<String, String>,
)

object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeState(state: RatchetSessionState): String {
        val dto = RatchetSessionStateDto(
            selfRatchetPrivateKey = encode(state.selfRatchetPrivateKey),
            selfRatchetPublicKey = encode(state.selfRatchetPublicKey),
            remoteRatchetPublicKey = state.remoteRatchetPublicKey?.let { encode(it) },
            rootKey = encode(state.rootKey),
            sendingChainKey = state.sendingChainKey?.let { encode(it) },
            receivingChainKey = state.receivingChainKey?.let { encode(it) },
            sendMessageNumber = state.sendMessageNumber,
            receiveMessageNumber = state.receiveMessageNumber,
            previousSendingChainLength = state.previousSendingChainLength,
            skippedMessageKeys = state.skippedMessageKeys.mapValues { encode(it.value) },
        )
        return json.encodeToString(dto)
    }

    fun decodeState(text: String): RatchetSessionState {
        val dto = json.decodeFromString<RatchetSessionStateDto>(text)
        return RatchetSessionState(
            selfRatchetPrivateKey = decode(dto.selfRatchetPrivateKey),
            selfRatchetPublicKey = decode(dto.selfRatchetPublicKey),
            remoteRatchetPublicKey = dto.remoteRatchetPublicKey?.let { decode(it) },
            rootKey = decode(dto.rootKey),
            sendingChainKey = dto.sendingChainKey?.let { decode(it) },
            receivingChainKey = dto.receivingChainKey?.let { decode(it) },
            sendMessageNumber = dto.sendMessageNumber,
            receiveMessageNumber = dto.receiveMessageNumber,
            previousSendingChainLength = dto.previousSendingChainLength,
            skippedMessageKeys = dto.skippedMessageKeys.mapValues { decode(it.value) },
        )
    }
}
