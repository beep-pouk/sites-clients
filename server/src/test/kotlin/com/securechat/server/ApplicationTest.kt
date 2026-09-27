package com.securechat.server

import com.securechat.crypto.IdentityKeyPair
import com.securechat.crypto.OneTimePreKeyPair
import com.securechat.crypto.RegistrationProof
import com.securechat.crypto.SignedPreKeyPair
import com.securechat.crypto.UserIdOwnershipProof
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationTest {

    private fun tempDbPath(): String = Files.createTempFile("securechat-test", ".db").toString()

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Mirrors exactly what the real Android client sends: a real identity, real signature. */
    private fun registerRequestFor(
        userId: String,
        identity: IdentityKeyPair = IdentityKeyPair.generate(),
        signedPreKey: SignedPreKeyPair = SignedPreKeyPair.generate(identity, keyId = 1),
        oneTimePreKeys: List<OneTimePreKeyPair> = emptyList(),
    ): RegisterRequest {
        val signature = RegistrationProof.sign(identity, signedPreKey.publicKey)
        return RegisterRequest(
            userId = userId,
            identitySigningKey = encode(identity.signingPublicKey),
            identityAgreementKey = encode(identity.agreementPublicKey),
            signedPreKey = SignedPreKeyDto(signedPreKey.keyId, encode(signedPreKey.publicKey), encode(signedPreKey.signature)),
            oneTimePreKeys = oneTimePreKeys.map { OneTimePreKeyDto(it.keyId, encode(it.publicKey)) },
            signature = encode(signature),
        )
    }

    private data class TestUser(val userId: String, val identity: IdentityKeyPair, val registerRequest: RegisterRequest)

    private fun testUser(userId: String, oneTimePreKeys: List<OneTimePreKeyPair> = emptyList()): TestUser {
        val identity = IdentityKeyPair.generate()
        val signedPreKey = SignedPreKeyPair.generate(identity, keyId = 1)
        return TestUser(userId, identity, registerRequestFor(userId, identity, signedPreKey, oneTimePreKeys))
    }

    private fun aliceUser() = testUser("alice")
    private fun bobUser() = testUser("bob", OneTimePreKeyPair.generateBatch(startId = 1, count = 2))

    private fun aliceRegister() = aliceUser().registerRequest

    private fun bobRegister() = bobUser().registerRequest

    /** Query string proving [user] owns their own userId, for the endpoints that require it. */
    private fun ownershipQuery(user: TestUser): String {
        val timestamp = System.currentTimeMillis()
        val signature = UserIdOwnershipProof.sign(user.identity, user.userId, timestamp)
        return "ts=$timestamp&sig=${URLEncoder.encode(encode(signature), "UTF-8")}"
    }

    @Test
    fun `register then fetch bundle returns and consumes a one-time prekey`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val registerResponse = client.post("/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(bobRegister())
        }
        assertEquals(HttpStatusCode.OK, registerResponse.status)

        val first = client.get("/v1/prekeys/bob").body<PreKeyBundleResponse>()
        assertNotNull(first.oneTimePreKey)

        val second = client.get("/v1/prekeys/bob").body<PreKeyBundleResponse>()
        assertNotNull(second.oneTimePreKey)
        assertNotEquals(first.oneTimePreKey!!.keyId, second.oneTimePreKey!!.keyId)

        val third = client.get("/v1/prekeys/bob").body<PreKeyBundleResponse>()
        assertNull(third.oneTimePreKey) // both one-time prekeys now consumed
    }

    @Test
    fun `fetching a bundle for an unknown user returns 404`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/v1/prekeys/nobody")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `sending and fetching messages round-trips an opaque envelope`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val bob = bobUser()
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(aliceRegister()) }
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(bob.registerRequest) }

        val send = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("bob", "alice", "base64-opaque-ciphertext", isHandshake = true))
        }
        assertEquals(HttpStatusCode.OK, send.status)
        val sendBody = send.body<SendMessageResponse>()

        val inbox = client.get("/v1/messages/bob?${ownershipQuery(bob)}").body<List<StoredMessageDto>>()
        assertEquals(1, inbox.size)
        assertEquals("base64-opaque-ciphertext", inbox[0].ciphertextEnvelope)
        assertEquals(sendBody.messageId, inbox[0].messageId)

        val ack = client.post("/v1/messages/bob/ack?${ownershipQuery(bob)}") {
            contentType(ContentType.Application.Json)
            setBody(AckRequest(listOf(sendBody.messageId)))
        }
        assertEquals(HttpStatusCode.OK, ack.status)

        val inboxAfterAck = client.get("/v1/messages/bob?${ownershipQuery(bob)}").body<List<StoredMessageDto>>()
        assertTrue(inboxAfterAck.isEmpty())
    }

    @Test
    fun `fetching or acking another user's messages without ownership proof is forbidden`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val bob = bobUser()
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(bob.registerRequest) }
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(aliceRegister()) }
        client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("bob", "alice", "top-secret-ciphertext", isHandshake = true))
        }

        val fetchWithoutProof = client.get("/v1/messages/bob")
        assertEquals(HttpStatusCode.Forbidden, fetchWithoutProof.status)

        val eve = aliceUser() // a different, unrelated identity - not bob's
        val fetchWithWrongProof = client.get("/v1/messages/bob?${ownershipQuery(eve.copy(userId = "bob"))}")
        assertEquals(HttpStatusCode.Forbidden, fetchWithWrongProof.status)

        val ackWithoutProof = client.post("/v1/messages/bob/ack") {
            contentType(ContentType.Application.Json)
            setBody(AckRequest(listOf(1L)))
        }
        assertEquals(HttpStatusCode.Forbidden, ackWithoutProof.status)

        // The message must still be sitting there, unread and undeleted, for its real owner.
        val realInbox = client.get("/v1/messages/bob?${ownershipQuery(bob)}").body<List<StoredMessageDto>>()
        assertEquals(1, realInbox.size)
    }

    @Test
    fun `sending a message to an unregistered recipient returns 404`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(aliceRegister()) }

        val send = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("nobody", "alice", "ciphertext", isHandshake = true))
        }
        assertEquals(HttpStatusCode.NotFound, send.status)
    }

    @Test
    fun `re-registering an existing userId with a different identity and no valid signature is rejected`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val victimRequest = registerRequestFor("victim")
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(victimRequest) }

        // Attacker has no idea what victim's real signing key is, so their own self-consistent
        // (but unrelated) registration request's signature won't verify against it.
        val attackerRequest = registerRequestFor("victim")
        val hijackAttempt = client.post("/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(attackerRequest)
        }
        assertEquals(HttpStatusCode.Forbidden, hijackAttempt.status)

        // The original identity must still be the one on file.
        val bundle = client.get("/v1/prekeys/victim").body<PreKeyBundleResponse>()
        assertEquals(victimRequest.identitySigningKey, bundle.identitySigningKey)
    }

    @Test
    fun `re-registering an existing userId with a valid continuity signature succeeds`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val identity = IdentityKeyPair.generate()
        val firstSignedPreKey = SignedPreKeyPair.generate(identity, keyId = 1)
        client.post("/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(registerRequestFor("rotating-user", identity, firstSignedPreKey))
        }

        // Same device, same identity, rotating its signed prekey - a completely normal operation.
        val rotatedSignedPreKey = SignedPreKeyPair.generate(identity, keyId = 2)
        val rotate = client.post("/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(registerRequestFor("rotating-user", identity, rotatedSignedPreKey))
        }
        assertEquals(HttpStatusCode.OK, rotate.status)

        val bundle = client.get("/v1/prekeys/rotating-user").body<PreKeyBundleResponse>()
        assertEquals(2, bundle.signedPreKey.keyId)
    }

    @Test
    fun `websocket push requires proof of ownership of the userId`() = testApplication {
        application { module(tempDbPath()) }
        val restClient = createClient { install(ContentNegotiation) { json() } }
        val wsClient = createClient { install(WebSockets) }

        val identity = IdentityKeyPair.generate()
        val signedPreKey = SignedPreKeyPair.generate(identity, keyId = 1)
        restClient.post("/v1/register") {
            contentType(ContentType.Application.Json)
            setBody(registerRequestFor("ws-user", identity, signedPreKey))
        }

        // No one else can subscribe to ws-user's push feed without their private signing key.
        wsClient.webSocket("/v1/ws/ws-user?ts=${System.currentTimeMillis()}&sig=bm90LWEtcmVhbC1zaWc=") {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }

        // The real owner, signing with their own identity, is let in and receives live pushes.
        restClient.post("/v1/register") { contentType(ContentType.Application.Json); setBody(aliceRegister()) }
        val timestamp = System.currentTimeMillis()
        val signature = Base64.getEncoder().encodeToString(UserIdOwnershipProof.sign(identity, "ws-user", timestamp))
        wsClient.webSocket("/v1/ws/ws-user?ts=$timestamp&sig=${URLEncoder.encode(signature, "UTF-8")}") {
            restClient.post("/v1/messages") {
                contentType(ContentType.Application.Json)
                setBody(SendMessageRequest("ws-user", "alice", "hello-push", isHandshake = false))
            }
            val frame = incoming.receive()
            assertTrue(frame is Frame.Text)
        }
    }

    @Test
    fun `unhandled exceptions never leak internal details to the client`() = testApplication {
        application { module(tempDbPath()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/register") {
            contentType(ContentType.Application.Json)
            setBody("{ this is not valid json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertTrue(body.error.isNotBlank())
        assertTrue("must not leak internal class/package names: ${body.error}", !body.error.contains("com.securechat"))
    }
}
