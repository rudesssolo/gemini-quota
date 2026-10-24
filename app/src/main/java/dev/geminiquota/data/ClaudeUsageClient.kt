package dev.geminiquota.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.util.concurrent.TimeUnit

/**
 * Reads Claude subscription usage from the signed-in web session.
 *
 * Two requests at most:
 * 1. `GET /api/organizations` — first organization uuid on the account (skipped
 *    when the id is already known).
 * 2. `GET /api/organizations/{orgId}/usage` — the 5-hour and weekly windows.
 *
 * Like the Gemini endpoint this is undocumented and can change without notice;
 * the parser fails loudly with a diagnostic snippet rather than showing zeros.
 */
class ClaudeUsageClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun fetchUsage(sessionKey: String, organizationId: String? = null): QuotaPage {
        val cookie = ClaudeCookies.normalize(sessionKey)
            ?: throw UsageException.AuthRequired("No claude.ai sessionKey cookie was supplied")
        val organization = organizationId?.takeIf { it.isNotBlank() }
            ?: discoverOrganization(cookie)
        return fetchUsagePage(cookie, organization)
    }

    private fun discoverOrganization(cookie: String): String {
        val result = httpClient.execute(
            request("api/organizations", cookie).build(),
            clock,
        )
        result.requireSuccess("Claude organizations request")

        val organizations = try {
            JSONArray(result.body)
        } catch (e: JSONException) {
            throw UsageException.ProtocolChanged(
                "Claude organizations response was not valid JSON",
                responseSnippet(result.body),
            )
        }
        return (0 until organizations.length())
            .mapNotNull { organizations.optJSONObject(it) }
            .mapNotNull { it.optString("uuid").takeIf { uuid -> uuid.isNotBlank() } }
            .firstOrNull()
            ?: throw UsageException.OrgNotFound()
    }

    private fun fetchUsagePage(cookie: String, organizationId: String): QuotaPage {
        val result = httpClient.execute(
            request("api/organizations/$organizationId/usage", cookie).build(),
            clock,
        )
        result.requireSuccess("Claude usage request")
        return ClaudeUsageParser.parse(result.body, clock())
    }

    private fun request(path: String, cookie: String): Request.Builder = Request.Builder()
        .url(baseUrl.newBuilder().addPathSegments(path.removePrefix("/")).build())
        .get()
        .header("Cookie", cookie)
        .header("Accept", "application/json")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Referer", origin())
        .header("User-Agent", USER_AGENT)

    private fun origin(): String = "${baseUrl.scheme}://${baseUrl.host}/"

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://claude.ai/".toHttpUrl()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
