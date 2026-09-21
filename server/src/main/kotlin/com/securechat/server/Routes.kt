package com.securechat.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.registerRoutes(storage: ServerStorage, connections: ConnectionRegistry, json: Json) {
    route("/v1") {
        post("/register") {
            val request = call.receive<RegisterRequest>()
            storage.registerUser(request)
            call.respond(HttpStatusCode.OK)
        }

        post("/prekeys/{userId}/upload") {
            val userId = call.parameters["userId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            if (!storage.userExists(userId)) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown user"))
            }
            val request = call.receive<UploadOneTimePreKeysRequest>()
            storage.addOneTimePreKeys(userId, request.oneTimePreKeys)
            call.respond(HttpStatusCode.OK)
        }

        get("/prekeys/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            val bundle = storage.fetchBundle(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown user"))
            call.respond(bundle)
        }

        post("/messages") {
            val request = call.receive<SendMessageRequest>()
            if (!storage.userExists(request.recipientUserId)) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown recipient"))
            }
            val stored = storage.storeMessage(request)
            connections.push(request.recipientUserId, json.encodeToString(stored))
            call.respond(SendMessageResponse(stored.messageId))
        }

        get("/messages/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            call.respond(storage.fetchMessages(userId))
        }

        post("/messages/{userId}/ack") {
            val userId = call.parameters["userId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            val request = call.receive<AckRequest>()
            storage.acknowledgeMessages(userId, request.messageIds)
            call.respond(HttpStatusCode.OK)
        }

        webSocket("/ws/{userId}") {
            val userId = call.parameters["userId"]
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing userId"))
                return@webSocket
            }
            connections.register(userId, this)
            try {
                // This socket is used for server -> client push only; inbound frames (pings
                // aside, handled by the WebSockets plugin) are simply drained and ignored.
                for (frame in incoming) { /* no-op */ }
            } finally {
                connections.unregister(userId, this)
            }
        }
    }
}
