package com.securechat.app.data.remote

import retrofit2.Response

/** Retrofit doesn't throw on non-2xx for `Response<T>` return types - callers must check. */
fun Response<*>.requireSuccessful(action: String) {
    if (!isSuccessful) {
        error("$action failed: HTTP ${code()} ${errorBody()?.string().orEmpty()}".trim())
    }
}
