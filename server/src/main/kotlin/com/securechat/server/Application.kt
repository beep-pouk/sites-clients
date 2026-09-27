package com.securechat.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.hsts.HSTS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

private const val MAX_REQUEST_BODY_BYTES = 256 * 1024L

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val databasePath = System.getenv("DATABASE_PATH") ?: "securechat.db"
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(databasePath)
    }.start(wait = true)
}

fun Application.module(databasePath: String = "securechat.db") {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    val storage = ServerStorage(databasePath)
    val connections = ConnectionRegistry()

    // Render (like most PaaS hosts) terminates TLS at its edge and forwards plain HTTP
    // internally, tagging the original client IP/scheme via X-Forwarded-*. Installed first so
    // every plugin after it (rate limiting by IP, HSTS's HTTPS check) sees the real values
    // instead of the edge's own.
    install(XForwardedHeaders)

    // Rejects an oversized request before it's ever buffered or parsed - the largest legitimate
    // body (a registration with a full one-time-prekey batch) is a few KB, so 256 KB leaves ample
    // headroom while still bounding how much a single request can force the server to hold.
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_REQUEST_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Request body too large"))
            finish()
        }
    }

    install(ContentNegotiation) { json(json) }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    install(CallLogging) { level = Level.INFO }

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
    }
    install(HSTS) {
        maxAgeInSeconds = 31_536_000 // 1 year, the standard long-lived HSTS duration
        includeSubDomains = true
    }

    install(RateLimit) {
        // Applies to every route not explicitly assigned one of the named buckets below -
        // generous enough for normal polling (the app fetches/acks roughly every 10s) while
        // still bounding a single client's request rate.
        global {
            requestKey { call -> call.request.origin.remoteHost }
            rateLimiter(limit = 120, refillPeriod = 60.seconds)
        }
        register(RegisterRateLimit) {
            requestKey { call -> call.request.origin.remoteHost }
            rateLimiter(limit = 5, refillPeriod = 60.seconds)
        }
        register(SendMessageRateLimit) {
            requestKey { call -> call.request.origin.remoteHost }
            rateLimiter(limit = 30, refillPeriod = 60.seconds)
        }
        register(PrekeyUploadRateLimit) {
            requestKey { call -> call.request.origin.remoteHost }
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
        }
    }

    install(StatusPages) {
        // Never echo exception messages back to the client - they can embed internal class/field
        // names (or worse) that are only useful to an attacker probing the API. The real cause
        // is still logged server-side for debugging.
        exception<BadRequestException> { call, cause ->
            call.application.log.warn("Bad request on ${call.request.uri}", cause)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception on ${call.request.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    routing {
        get("/health") { call.respond(HttpStatusCode.OK) }
        registerRoutes(storage, connections, json)
    }
}
