package dev.geminiquota.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A page plus the value-free shape of the payload it came from. */
data class ClaudeUsage(val page: QuotaPage, val shape: String)

/**
 * Reads Claude subscription usage from the signed-in web session.
 *
 * Two requests normally:
 * 1. `GET /api/organizations` — first organization uuid on the account (skipped
 *    when the id is already known).
 * 2. `GET /api/organizations/{orgId}/usage` — the 5-hour and weekly windows.
 *
 * A third request (`/api/bootstrap/{orgId}/app_start`) runs only when the usage
 * payload carries no windows, to name the plan: as of August 2026 the **free**
 * tier answers with every window set to `null`, so the widget has to say
 * "this plan publishes nothing" instead of pretending the account is unused.
 *
 * Like the Gemini endpoint this is undocumented and can change without notice;
 * the parser fails loudly with a diagnostic snippet rather than showing zeros.
 */
class ClaudeUsageClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun fetchUsage(sessionKey: String, organizationId: String? = null): QuotaPage =
        fetchUsageDetailed(sessionKey, organizationId).page

    fun fetchUsageDetailed(sessionKey: String, organizationId: String? = null): ClaudeUsage {
        val cookie = ClaudeCookies.cookieHeader(sessionKey)
            ?: throw UsageException.AuthRequired("No claude.ai sessionKey cookie was supplied")
        val organization = organizationId?.takeIf { it.isNotBlank() }
            ?: discoverOrganization(cookie)

        val result = httpClient.execute(
            request("api/organizations/$organization/usage", cookie).build(),
            clock,
        )
        result.requireSuccess("Claude usage request")
        val page = ClaudeUsageParser.parse(result.body, clock())

        val withTier = if (page.hasAnyWindow) {
            page
        } else {
            page.copy(tier = discoverTier(cookie, organization) ?: page.tier)
        }
        return ClaudeUsage(withTier, ClaudeUsageParser.shape(result.body))
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

    /**
     * Names the plan from `app_start`, the call the web app itself makes on load:
     * `account.memberships[].organization` carries `capabilities` and
     * `rate_limit_tier`. Best effort — a failure here only leaves the tier blank.
     */
    private fun discoverTier(cookie: String, organizationId: String): String? = runCatching {
        val result = httpClient.execute(
            request(bootstrapUrl(organizationId), cookie).build(),
            clock,
        )
        if (result.code !in 200..299) return@runCatching null

        val memberships = JSONObject(result.body)
            .optJSONObject("account")
            ?.optJSONArray("memberships")
            ?: return@runCatching null
        val organizations = (0 until memberships.length()).mapNotNull { index ->
            memberships.optJSONObject(index)?.optJSONObject("organization")
        }
        val organization = organizations.firstOrNull { it.optString("uuid") == organizationId }
            ?: organizations.firstOrNull()
            ?: return@runCatching null

        val capabilities = organization.optJSONArray("capabilities")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val rateLimit = organization.optString("rate_limit_tier")

        when {
            !organization.optString("raven_type").isBlank() -> "Team"
            "claude_max" in capabilities -> if ("5x" in rateLimit) "Max (5x)" else "Max (20x)"
            "claude_pro" in capabilities -> "Pro"
            else -> "Free"
        }
    }.getOrNull()

    private fun request(path: String, cookie: String): Request.Builder =
        request(baseUrl.newBuilder().addPathSegments(path.removePrefix("/")).build(), cookie)

    private fun request(url: HttpUrl, cookie: String): Request.Builder = Request.Builder()
        .url(url)
        .get()
        .header("Cookie", cookie)
        .header("Accept", "application/json")
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Referer", origin())
        .header("User-Agent", USER_AGENT)

    /** `app_start` takes a query parameter, which `addPathSegments` would escape. */
    private fun bootstrapUrl(organizationId: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("api/bootstrap/$organizationId/app_start")
        .addQueryParameter("statsig_hashing_algorithm", "djb2")
        .build()

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
