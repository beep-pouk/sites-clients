package com.securechat.server

import io.ktor.server.plugins.ratelimit.RateLimitName

/** Named rate-limit buckets applied to the highest-value abuse targets; everything else falls
 *  back to the more generous global limit installed alongside these in [Application.module]. */
val RegisterRateLimit = RateLimitName("register")
val SendMessageRateLimit = RateLimitName("send-message")
val PrekeyUploadRateLimit = RateLimitName("prekey-upload")
