package com.securechat.app.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClient {
    fun create(baseUrl: String, json: Json): ServerApi {
        // No logging interceptor: even at a metadata-only level, request URLs carry a userId and
        // a signed ownership proof (ts/sig) in their query string, and logcat is readable by other
        // apps on some older/rooted devices - there's no operational need to write any of that out.
        val client = OkHttpClient.Builder().build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(ServerApi::class.java)
    }

    fun webSocketHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
