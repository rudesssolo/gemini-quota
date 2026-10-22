package dev.geminiquota.ui

import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import dev.geminiquota.R
import dev.geminiquota.data.ErrorKind
import dev.geminiquota.data.UsageUiState
import dev.geminiquota.data.UsageWindow
import dev.geminiquota.databinding.ActivityMainBinding
import dev.geminiquota.databinding.ViewQuotaCardBinding

/** Renders [UsageUiState] into the dashboard. */
class MainRenderer(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {

    fun render(state: UsageUiState) {
        binding.refreshingBar.isVisible = state.isRefreshing
        binding.connectCard.isVisible = !state.isSignedIn || state.authRequired
        binding.signedInHint.isVisible = state.isSignedIn

        val snapshot = state.snapshot
        val plan = UsageFormat.planLabel(snapshot?.tier, activity)
        if (plan != null && state.isSignedIn) {
            binding.planChip.text = plan
            binding.planChip.isVisible = true
        } else {
            binding.planChip.isVisible = false
        }

        renderWindow(
            binding.fiveCard,
            snapshot?.fiveHour,
            activity.getString(R.string.window_five_hour),
        )
        renderWindow(
            binding.weeklyCard,
            snapshot?.weekly,
            activity.getString(R.string.window_weekly),
        )

        val errorKind = state.errorKind
        if (errorKind != null) {
            binding.errorCard.isVisible = true
            binding.errorText.setText(messageFor(errorKind))
            binding.errorDetailsButton.isVisible = !state.errorDetail.isNullOrBlank()
        } else {
            binding.errorCard.isVisible = false
        }

        binding.updatedText.text = snapshot?.let {
            val relative = DateUtils.getRelativeTimeSpanString(
                it.fetchedAtEpochMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
            activity.getString(R.string.updated_relative, relative.toString())
        }.orEmpty()
    }

    private fun renderWindow(
        card: ViewQuotaCardBinding,
        window: UsageWindow?,
        title: String,
    ) {
        card.cardTitle.text = title
        card.cardPercent.text = UsageFormat.percentText(activity, window)

        if (window != null) {
            card.cardPercent.setTextColor(
                activity.getColor(
                    UsageFormat.severityColorRes(UsageFormat.severity(window)),
                ),
            )
            card.cardProgress.progress = window.usedPercent
            card.cardProgress.visibility = View.VISIBLE

            val base = UsageFormat.countdownBase(window)
            if (base != null) {
                card.cardChrono.isVisible = true
                card.cardChrono.format = activity.getString(R.string.resets_in)
                card.cardChrono.isCountDown = true
                card.cardChrono.base = base
                card.cardChrono.start()
                card.cardUnknown.isVisible = false
            } else {
                card.cardChrono.stop()
                card.cardChrono.isVisible = false
                card.cardUnknown.setText(
                    if (window.resetAtEpochMs == null) {
                        R.string.reset_unknown
                    } else {
                        R.string.reset_soon
                    },
                )
                card.cardUnknown.isVisible = true
            }
        } else {
            card.cardPercent.setTextColor(activity.getColor(R.color.text_secondary))
            card.cardProgress.progress = 0
            card.cardChrono.stop()
            card.cardChrono.isVisible = false
            card.cardUnknown.setText(R.string.window_not_reported)
            card.cardUnknown.isVisible = true
        }
    }

    private fun messageFor(kind: ErrorKind): Int = when (kind) {
        ErrorKind.AUTH -> R.string.error_auth
        ErrorKind.NETWORK -> R.string.error_network
        ErrorKind.RATE_LIMITED -> R.string.error_rate_limited
        ErrorKind.SERVER -> R.string.error_server
        ErrorKind.PROTOCOL -> R.string.error_protocol
        ErrorKind.UNKNOWN -> R.string.error_unknown
    }
}
