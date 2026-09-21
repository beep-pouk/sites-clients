package com.securechat.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ServerApi {
    @POST("v1/register")
    suspend fun register(@Body request: RegisterRequest): Response<Unit>

    @POST("v1/prekeys/{userId}/upload")
    suspend fun uploadOneTimePreKeys(
        @Path("userId") userId: String,
        @Body request: UploadOneTimePreKeysRequest,
    ): Response<Unit>

    @GET("v1/prekeys/{userId}")
    suspend fun fetchBundle(@Path("userId") userId: String): Response<PreKeyBundleResponse>

    @POST("v1/messages")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<SendMessageResponse>

    @GET("v1/messages/{userId}")
    suspend fun fetchMessages(@Path("userId") userId: String): Response<List<StoredMessageDto>>

    @POST("v1/messages/{userId}/ack")
    suspend fun acknowledgeMessages(@Path("userId") userId: String, @Body request: AckRequest): Response<Unit>
}
