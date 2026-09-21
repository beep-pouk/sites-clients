package com.securechat.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ApplicationTest {

    private fun tempDbPath(): String = Files.createTempFile("securechat-test", ".db").toString()

    private fun aliceRegister() = RegisterRequest(
        userId = "alice",
        identitySigningKey = "sig-alice",
        identityAgreementKey = "agree-alice",
        signedPreKey = SignedPreKeyDto(1, "spk-pub-alice", "spk-sig-alice"),
        oneTimePreKeys = emptyList(),
    )

    private fun bobRegister() = RegisterRequest(
        userId = "bob",
        identitySigningKey = "sig-bob",
        identityAgreementKey = "agree-bob",
        signedPreKey = SignedPreKeyDto(1, "spk-pub-bob", "spk-sig-bob"),
        oneTimePreKeys = listOf(OneTimePreKeyDto(1, "otk-1-bob"), OneTimePreKeyDto(2, "otk-2-bob")),
    )

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

        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(aliceRegister()) }
        client.post("/v1/register") { contentType(ContentType.Application.Json); setBody(bobRegister()) }

        val send = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("bob", "alice", "base64-opaque-ciphertext", isHandshake = true))
        }
        assertEquals(HttpStatusCode.OK, send.status)
        val sendBody = send.body<SendMessageResponse>()

        val inbox = client.get("/v1/messages/bob").body<List<StoredMessageDto>>()
        assertEquals(1, inbox.size)
        assertEquals("base64-opaque-ciphertext", inbox[0].ciphertextEnvelope)
        assertEquals(sendBody.messageId, inbox[0].messageId)

        val ack = client.post("/v1/messages/bob/ack") {
            contentType(ContentType.Application.Json)
            setBody(AckRequest(listOf(sendBody.messageId)))
        }
        assertEquals(HttpStatusCode.OK, ack.status)

        val inboxAfterAck = client.get("/v1/messages/bob").body<List<StoredMessageDto>>()
        assertTrue(inboxAfterAck.isEmpty())
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
}
