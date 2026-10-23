package dev.geminiquota.ui

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
            UsageFormat.updatedLabel(activity, it.fetchedAtEpochMs)
        }.orEmpty()
    }

    private fun renderWindow(
        card: ViewQuotaCardBinding,
        window: UsageWindow?,
        title: String,
    ) {
        card.cardTitle.text = title

        val targetColor = activity.getColor(
            if (window != null) {
                UsageFormat.severityColorRes(UsageFormat.severity(window))
            } else {
                R.color.text_secondary
            },
        )
        card.cardProgress.visibility = View.VISIBLE

        if (window != null) {
            QuotaProgressAnimator.animate(
                progressBar = card.cardProgress,
                percentText = card.cardPercent,
                targetPercent = window.usedPercent,
                targetColor = targetColor,
                format = { activity.getString(R.string.percent_format, it) },
            )
        } else {
            QuotaProgressAnimator.cancel(card.cardProgress)
            card.cardProgress.progress = 0
            card.cardPercent.text = UsageFormat.percentText(activity, null)
            card.cardPercent.setTextColor(targetColor)
        }

        // Absolute time, matching the widget: a countdown stops being true the
        // moment the process is frozen.
        card.cardReset.text = UsageFormat.resetLabel(activity, window)
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
