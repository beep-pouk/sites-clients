package com.securechat.server

import com.securechat.crypto.UserIdOwnershipProof
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire names for the ownership-proof headers; mirrored as literals in the app's ServerApi and
 *  WebSocketClient, same as every other wire-format detail shared between server and app. */
const val OWNERSHIP_TIMESTAMP_HEADER = "X-Ownership-Timestamp"
const val OWNERSHIP_SIGNATURE_HEADER = "X-Ownership-Signature"

/**
 * True if the request carries a valid, fresh [UserIdOwnershipProof] for [userId] (headers
 * [OWNERSHIP_TIMESTAMP_HEADER] and [OWNERSHIP_SIGNATURE_HEADER]), verified against whatever
 * signing key is currently on file for that userId. Guards every endpoint that reads or mutates a
 * specific user's own data (their message queue, their prekey pool, their push subscription) -
 * only `GET /prekeys/{userId}` is deliberately exempt, since publishing a fetchable public bundle
 * is the whole point of that one.
 *
 * Carried as headers rather than query parameters so this signature never ends up embedded in a
 * URL - and therefore never in server access logs, proxy logs, or browser/webview history, all of
 * which routinely capture the full request line including its query string but not its headers.
 */
private fun ApplicationCall.hasValidOwnershipProof(storage: ServerStorage, userId: String): Boolean {
    val timestamp = request.headers[OWNERSHIP_TIMESTAMP_HEADER]?.toLongOrNull() ?: return false
    val signature = request.headers[OWNERSHIP_SIGNATURE_HEADER] ?: return false
    val identitySigningKey = storage.getIdentitySigningKey(userId) ?: return false
    return runCatching {
        UserIdOwnershipProof.verify(
            identitySigningKey = Base64.getDecoder().decode(identitySigningKey),
            userId = userId,
            timestampMillis = timestamp,
            signature = Base64.getDecoder().decode(signature),
            now = System.currentTimeMillis(),
        )
    }.getOrDefault(false)
}

fun Route.registerRoutes(storage: ServerStorage, connections: ConnectionRegistry, json: Json) {
    route("/v1") {
        rateLimit(RegisterRateLimit) {
            post("/register") {
                val request = call.receive<RegisterRequest>()
                request.validate()
                val existingSigningKey = storage.getIdentitySigningKey(request.userId)
                if (existingSigningKey != null && !RegistrationAuth.verifiesAgainstExistingIdentity(request, existingSigningKey)) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("Registration signature does not match the identity already on file for this userId"),
                    )
                }
                storage.registerUser(request)
                call.respond(HttpStatusCode.OK)
            }
        }

        rateLimit(PrekeyUploadRateLimit) {
            post("/prekeys/{userId}/upload") {
                val userId = call.parameters["userId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
                validateUserId(userId)
                if (!call.hasValidOwnershipProof(storage, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("missing or invalid ownership proof"))
                }
                val request = call.receive<UploadOneTimePreKeysRequest>()
                validateOneTimePreKeys(request.oneTimePreKeys)
                storage.addOneTimePreKeys(userId, request.oneTimePreKeys)
                call.respond(HttpStatusCode.OK)
            }
        }

        get("/prekeys/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            validateUserId(userId)
            val bundle = storage.fetchBundle(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown user"))
            call.respond(bundle)
        }

        rateLimit(SendMessageRateLimit) {
            post("/messages") {
                val request = call.receive<SendMessageRequest>()
                request.validate()
                if (!storage.userExists(request.recipientUserId)) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown recipient"))
                }
                // The claimed senderUserId is otherwise just a free-text label an attacker could
                // set to any registered identity's id - including one the recipient already has
                // an established session with - to get a bogus/garbage envelope routed to them
                // under that trusted label. The client can't tell an authenticated-forgery attempt
                // apart from a legitimate re-handshake, so it would silently overwrite - and
                // destroy - the real session. Requiring proof of the sender's own identity here
                // closes that off at the source, the same way ownership proof already guards
                // reading/acking a recipient's own queue.
                if (!call.hasValidOwnershipProof(storage, request.senderUserId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("missing or invalid sender proof"))
                }
                if (storage.countQueuedMessages(request.recipientUserId) >= MAX_QUEUED_MESSAGES_PER_RECIPIENT) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("recipient's message queue is full"))
                }
                val stored = storage.storeMessage(request)
                connections.push(request.recipientUserId, json.encodeToString(stored))
                call.respond(SendMessageResponse(stored.messageId))
            }
        }

        get("/messages/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            validateUserId(userId)
            if (!call.hasValidOwnershipProof(storage, userId)) {
                return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("missing or invalid ownership proof"))
            }
            call.respond(storage.fetchMessages(userId))
        }

        post("/messages/{userId}/ack") {
            val userId = call.parameters["userId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing userId"))
            validateUserId(userId)
            if (!call.hasValidOwnershipProof(storage, userId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("missing or invalid ownership proof"))
            }
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
            if (runCatching { validateUserId(userId) }.isFailure) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid userId"))
                return@webSocket
            }
            if (!call.hasValidOwnershipProof(storage, userId)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing or invalid ownership proof"))
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
