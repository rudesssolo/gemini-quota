package dev.geminiquota.data

/** A quota source the app can talk to. */
enum class ProviderId(val key: String) {
    GEMINI("gemini"),
    CLAUDE("claude"),
}

/** Which quota window a number belongs to, independent of the provider. */
enum class QuotaWindowId {
    FIVE_HOUR,
    WEEKLY,
    WEEKLY_OPUS,
}

data class QuotaWindow(
    val id: QuotaWindowId,
    val usedPercent: Int,
    val resetAtEpochMs: Long?,
)

/**
 * One provider's numbers, ready to render as a page of the widget or of the
 * dashboard.
 *
 * Gemini reports two windows and a subscription tier; Claude reports a 5-hour
 * session, a weekly window and — on Max plans — a separate weekly Opus window.
 * Modelling windows as a list keeps both providers on the same rendering path
 * instead of teaching every view about each service.
 */
data class QuotaPage(
    val provider: ProviderId,
    val tier: String?,
    val windows: List<QuotaWindow>,
    val fetchedAtEpochMs: Long,
    val source: String,
) {
    val hasAnyWindow: Boolean get() = windows.isNotEmpty()

    fun window(id: QuotaWindowId): QuotaWindow? = windows.firstOrNull { it.id == id }

    /** Worst usage across the reported windows, for the page indicator and the dot. */
    val worstPercent: Int get() = windows.maxOfOrNull { it.usedPercent } ?: 0

    fun nextResetAtEpochMs(): Long? = windows.mapNotNull { it.resetAtEpochMs }.minOrNull()
}

/** Bridges the existing Gemini model into the provider-neutral one. */
fun UsageSnapshot.toQuotaPage(): QuotaPage = QuotaPage(
    provider = ProviderId.GEMINI,
    tier = tier,
    windows = listOfNotNull(
        fiveHour?.let { QuotaWindow(QuotaWindowId.FIVE_HOUR, it.usedPercent, it.resetAtEpochMs) },
        weekly?.let { QuotaWindow(QuotaWindowId.WEEKLY, it.usedPercent, it.resetAtEpochMs) },
    ),
    fetchedAtEpochMs = fetchedAtEpochMs,
    source = source.name,
)
