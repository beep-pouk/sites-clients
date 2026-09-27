package com.securechat.app.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClient {
    fun create(baseUrl: String, json: Json): ServerApi {
        val logging = HttpLoggingInterceptor().apply {
            // BASIC only: message bodies are encrypted envelopes and must never hit logcat.
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(ServerApi::class.java)
    }

    fun webSocketHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
