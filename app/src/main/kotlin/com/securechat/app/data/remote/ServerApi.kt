package com.securechat.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** Wire names for the ownership-proof headers; mirrored as literals in the server's Routes.kt. */
internal const val OWNERSHIP_TIMESTAMP_HEADER = "X-Ownership-Timestamp"
internal const val OWNERSHIP_SIGNATURE_HEADER = "X-Ownership-Signature"

interface ServerApi {
    @POST("v1/register")
    suspend fun register(@Body request: RegisterRequest): Response<Unit>

    /**
     * The ownership-proof headers prove to the server that the caller owns [userId] (see
     * [OwnershipAuth]) - without them, anyone who learned a userId could pollute that user's
     * one-time-prekey pool, read or delete their queued messages, or eavesdrop on their delivery
     * metadata via the websocket. Carried as headers, not query parameters, so the signature never
     * ends up embedded in a URL that access logs or proxies might record.
     */
    @POST("v1/prekeys/{userId}/upload")
    suspend fun uploadOneTimePreKeys(
        @Path("userId") userId: String,
        @Header(OWNERSHIP_TIMESTAMP_HEADER) ts: Long,
        @Header(OWNERSHIP_SIGNATURE_HEADER) sig: String,
        @Body request: UploadOneTimePreKeysRequest,
    ): Response<Unit>

    @GET("v1/prekeys/{userId}")
    suspend fun fetchBundle(@Path("userId") userId: String): Response<PreKeyBundleResponse>

    /**
     * The ownership-proof headers prove the caller owns [SendMessageRequest.senderUserId] -
     * without them, anyone could claim to be an arbitrary registered user and route a forged
     * envelope to one of that user's real contacts under their name.
     */
    @POST("v1/messages")
    suspend fun sendMessage(
        @Header(OWNERSHIP_TIMESTAMP_HEADER) ts: Long,
        @Header(OWNERSHIP_SIGNATURE_HEADER) sig: String,
        @Body request: SendMessageRequest,
    ): Response<SendMessageResponse>

    @GET("v1/messages/{userId}")
    suspend fun fetchMessages(
        @Path("userId") userId: String,
        @Header(OWNERSHIP_TIMESTAMP_HEADER) ts: Long,
        @Header(OWNERSHIP_SIGNATURE_HEADER) sig: String,
    ): Response<List<StoredMessageDto>>

    @POST("v1/messages/{userId}/ack")
    suspend fun acknowledgeMessages(
        @Path("userId") userId: String,
        @Header(OWNERSHIP_TIMESTAMP_HEADER) ts: Long,
        @Header(OWNERSHIP_SIGNATURE_HEADER) sig: String,
        @Body request: AckRequest,
    ): Response<Unit>
}
