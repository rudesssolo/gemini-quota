package dev.geminiquota.ui

import android.content.Context
import androidx.annotation.StringRes
import dev.geminiquota.R
import dev.geminiquota.data.QuotaPage
import dev.geminiquota.data.QuotaWindowId

/** Provider-neutral formatting of a [QuotaPage] for the dialog and the widget pages. */
object QuotaPageFormat {

    @StringRes
    fun shortLabel(id: QuotaWindowId): Int = when (id) {
        QuotaWindowId.FIVE_HOUR -> R.string.probe_window_five
        QuotaWindowId.WEEKLY -> R.string.probe_window_weekly
        QuotaWindowId.WEEKLY_OPUS -> R.string.probe_window_weekly_opus
    }

    /** `5-hour 42% · Weekly 24%` — compact enough for a status line. */
    fun summary(context: Context, page: QuotaPage): String =
        page.windows.joinToString(" · ") { window ->
            "${context.getString(shortLabel(window.id))} ${window.usedPercent}%"
        }
}
