package dev.geminiquota.data

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads Gemini app usage limits from the signed-in web session.
 *
 * Order of attempts:
 * 1. GEMINI_WEB_GRAPHQL `UsagePageQuery` (single GET, no CSRF token).
 * 2. batchexecute RPC `jSf9Qc`, which needs bootstrap tokens scraped from the
 *    usage page.
 *
 * Google does not publish an API for consumer subscription usage, so these are
 * the same internal endpoints the usage page itself calls.
 */
class GeminiUsageClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun fetchUsage(cookieHeader: String): UsageSnapshot {
        val cookie = CookieUtils.normalize(cookieHeader)
        if (!CookieUtils.looksLikeGeminiSession(cookie)) {
            throw UsageException.AuthRequired("No Gemini session cookies were supplied")
        }

        val now = clock()
        var graphqlFailure: UsageException.ProtocolChanged? = null
        try {
            return fetchGraphql(cookie, now)
        } catch (e: UsageException.ProtocolChanged) {
            graphqlFailure = e
        }
        return fetchBatchexecute(cookie, now, graphqlFailure)
    }

    private fun fetchGraphql(cookie: String, now: Long): UsageSnapshot {
        val url = baseUrl.newBuilder()
            .addPathSegments(GRAPHQL_PATH.removePrefix("/"))
            .addQueryParameter("query_signature", QUERY_SIGNATURE)
            .addQueryParameter("variables", GRAPHQL_VARIABLES)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Referer", usageUrl().toString())
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val result = httpClient.execute(request, clock)
        result.requireSuccess("Gemini usage request")
        return GraphqlUsageParser.parse(result.body, now)
    }

    private fun fetchBatchexecute(
        cookie: String,
        now: Long,
        graphqlFailure: UsageException.ProtocolChanged?,
    ): UsageSnapshot {
        val pageRequest = Request.Builder()
            .url(usageUrl())
            .get()
            .header("Cookie", cookie)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val page = httpClient.execute(pageRequest, clock)
        page.requireSuccess("Gemini usage page")
        if (page.finalHost != null && page.finalHost != baseUrl.host) {
            throw UsageException.AuthRequired(
                "Gemini redirected to ${page.finalHost}; the session has expired",
            )
        }

        val tokens = WizTokenExtractor.extract(page.body)
            ?: throw UsageException.AuthRequired(
                "Gemini usage page had no session token; sign in again",
            )

        val requestId = (100_000..9_000_000).random()
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegments(BATCHEXECUTE_PATH.removePrefix("/"))
            .addQueryParameter("rpcids", BatchexecuteUsageParser.RPC_ID)
            .addQueryParameter("source-path", USAGE_PATH)
            .addQueryParameter("hl", tokens.locale?.takeIf { it.isNotBlank() } ?: "en")
            .addQueryParameter("_reqid", requestId.toString())
            .addQueryParameter("rt", "c")
        tokens.buildLabel?.takeIf { it.isNotBlank() }?.let {
            urlBuilder.addQueryParameter("bl", it)
        }
        tokens.sessionId?.takeIf { it.isNotBlank() }?.let {
            urlBuilder.addQueryParameter("f.sid", it)
        }

        val form = FormBody.Builder()
            .add("f.req", """[[["${BatchexecuteUsageParser.RPC_ID}","[]",null,"generic"]]]""")
            .add("at", tokens.requestToken)
            .build()

        val request = Request.Builder()
            .url(urlBuilder.build())
            .post(form)
            .header("Cookie", cookie)
            .header("X-Same-Domain", "1")
            .header("Origin", "${baseUrl.scheme}://${baseUrl.host}")
            .header("Referer", usageUrl().toString())
            .header("Accept", "*/*")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val result = httpClient.execute(request, clock)
        result.requireSuccess("Gemini usage RPC")
        if (result.body.trimStart().startsWith("<")) {
            throw UsageException.AuthRequired("Gemini returned a sign-in page for the usage RPC")
        }

        return try {
            BatchexecuteUsageParser.parse(result.body, now)
        } catch (e: UsageException.ProtocolChanged) {
            if (graphqlFailure != null) {
                throw UsageException.ProtocolChanged(
                    message = "${e.message}; GraphQL also failed: ${graphqlFailure.message}",
                    detail = e.detail ?: graphqlFailure.detail,
                )
            }
            throw e
        }
    }

    private fun usageUrl(): HttpUrl = baseUrl.newBuilder().addPathSegment("usage").build()

    companion object {
        const val DESKTOP_USER_AGENT = USER_AGENT

        private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

        val DEFAULT_BASE_URL: HttpUrl = "https://gemini.google.com/".toHttpUrl()

        // Persisted query signature of the Gemini app UsagePageQuery.
        private const val QUERY_SIGNATURE = "2/dWbCvkVB0X16r9NPnxifeOAAqdCqMf0xeWvau9yPy7g="

        private const val GRAPHQL_PATH =
            "/_/BardChatUi/graphql/schemas/GEMINI_WEB_GRAPHQL/executeQuery"
        private const val BATCHEXECUTE_PATH = "/_/BardChatUi/data/batchexecute"
        private const val USAGE_PATH = "/usage"

        private const val GRAPHQL_VARIABLES =
            """{"g1Request":{"entrypoints":["GEMINI_USAGE_SETTINGS_UPGRADE_BANNER"]},""" +
                """"workspaceRequest":{"recommendationsForSurfaceRequests":[]}}"""

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
