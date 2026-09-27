package com.securechat.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitEnforcementTest {
    @Test
    fun `register bucket enforces its own tighter limit, not the generous global one`() = testApplication {
        application { module(databasePath = ":memory:") }
        val codes = (1..7).map { i ->
            val resp: HttpResponse = client.post("/v1/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"probe-$i","identitySigningKey":"AA==","identityAgreementKey":"AA==","signedPreKey":{"publicKey":"AA==","signature":"AA=="},"oneTimePreKeys":[],"signature":"AA=="}""")
            }
            resp.status.value
        }
        println("codes=$codes")
        assertEquals("6th register request in the same window should be rate-limited", 429, codes[5])
    }
}
