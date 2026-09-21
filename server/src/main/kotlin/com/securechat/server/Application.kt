package com.securechat.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

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

    install(ContentNegotiation) { json(json) }
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    install(CallLogging) { level = Level.INFO }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "internal error"))
        }
    }

    routing {
        get("/health") { call.respond(HttpStatusCode.OK) }
        registerRoutes(storage, connections, json)
    }
}
