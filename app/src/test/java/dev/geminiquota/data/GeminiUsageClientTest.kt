package dev.geminiquota.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class GeminiUsageClientTest {

    private lateinit var server: MockWebServer

    private val cookie = "SID=abc; HSID=def; __Secure-1PSID=xyz"
    private val clock = 1_790_000_000_000L

    private val graphqlBody = """
        [{"data":{"viewer":{"rpcs":{"getUsageInfo":{
          "subscriptionTier":"TIER1",
          "pools":[
            {"poolType":"GXU_FIVE_HOURLY","percentageUsed":0.42,
             "gxu":{"windowRefreshesAt":"2026-10-22T23:00:00Z"}},
            {"poolType":"GXU_WEEKLY","percentageUsed":0.10,
             "gxu":{"windowRefreshesAt":"2026-10-26T00:00:00Z"}}
          ]}}}}}]
    """.trimIndent()

    private val usagePage = """
        <html><script>window.WIZ_global_data = {
          "SNlM0e":"token-123",
          "cfb2h":"build-label",
          "FdrFJe":"-987654321",
          "GGcqce":"en"
        };</script></html>
    """.trimIndent()

    private val rpcBody = """
        )]}'

        123
        [["wrb.fr","jSf9Qc","[null,[[0,0.5,1,[[1761260400,0]]],[0,0.2,2,[[1761606000,0]]]]]",null,null,null,"generic"]]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): GeminiUsageClient = GeminiUsageClient(
        baseUrl = server.url("/"),
        httpClient = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build(),
        clock = { clock },
    )

    @Test
    fun `reads usage over graphql and sends the session cookie`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(graphqlBody))

        val snapshot = client().fetchUsage(cookie)

        assertEquals(UsageSource.GRAPHQL, snapshot.source)
        assertEquals(42, snapshot.fiveHour?.usedPercent)
        assertEquals(
            Instant.parse("2026-10-22T23:00:00Z").toEpochMilli(),
            snapshot.fiveHour?.resetAtEpochMs,
        )
        assertEquals(clock, snapshot.fetchedAtEpochMs)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(cookie, request.getHeader("Cookie"))
        assertTrue(request.path.orEmpty().contains("query_signature="))
        assertTrue(request.path.orEmpty().contains("GEMINI_USAGE_SETTINGS_UPGRADE_BANNER"))
        assertEquals(server.url("/usage").toString(), request.getHeader("Referer"))
    }

    @Test
    fun `empty 200 is treated as an expired session`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        assertThrows(GeminiUsageException.AuthRequired::class.java) {
            client().fetchUsage(cookie)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `html 200 is treated as an expired session`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Sign in</html>"))

        assertThrows(GeminiUsageException.AuthRequired::class.java) {
            client().fetchUsage(cookie)
        }
    }

    @Test
    fun `401 is an auth failure`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(GeminiUsageException.AuthRequired::class.java) {
            client().fetchUsage(cookie)
        }
    }

    @Test
    fun `429 carries retry after`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val error = assertThrows(GeminiUsageException.RateLimited::class.java) {
            client().fetchUsage(cookie)
        }
        assertEquals(120L, error.retryAfterSeconds)
    }

    @Test
    fun `5xx is a server failure`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val error = assertThrows(GeminiUsageException.Server::class.java) {
            client().fetchUsage(cookie)
        }
        assertEquals(503, error.statusCode)
    }

    @Test
    fun `falls back to batchexecute when graphql changes shape`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""[{"data":{"viewer":{"rpcs":{}}}}]"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(usagePage))
        server.enqueue(MockResponse().setResponseCode(200).setBody(rpcBody))

        val snapshot = client().fetchUsage(cookie)

        assertEquals(UsageSource.BATCHEXECUTE, snapshot.source)
        assertEquals(50, snapshot.fiveHour?.usedPercent)
        assertEquals(20, snapshot.weekly?.usedPercent)

        val graphqlRequest = server.takeRequest()
        val pageRequest = server.takeRequest()
        val rpcRequest = server.takeRequest()
        assertTrue(graphqlRequest.path.orEmpty().contains("query_signature="))
        assertEquals("/usage", pageRequest.path)
        assertTrue(rpcRequest.path.orEmpty().startsWith("/_/BardChatUi/data/batchexecute"))
        assertTrue(rpcRequest.path.orEmpty().contains("rpcids=jSf9Qc"))
        assertTrue(rpcRequest.path.orEmpty().contains("bl=build-label"))
        assertEquals("1", rpcRequest.getHeader("X-Same-Domain"))

        val body = rpcRequest.body.readUtf8()
        assertTrue(body.contains("at=token-123"))
        assertTrue(body.contains("jSf9Qc"))
    }

    @Test
    fun `missing bootstrap token is an auth failure`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""[{"data":{"viewer":{"rpcs":{}}}}]"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Sign in</html>"))

        assertThrows(GeminiUsageException.AuthRequired::class.java) {
            client().fetchUsage(cookie)
        }
    }

    @Test
    fun `rejects a cookie without a google session`() {
        val error = assertThrows(GeminiUsageException.AuthRequired::class.java) {
            client().fetchUsage("foo=bar")
        }
        assertNotNull(error.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `network failures are wrapped`() {
        server.shutdown()
        assertThrows(GeminiUsageException.Network::class.java) {
            client().fetchUsage(cookie)
        }
    }
}
