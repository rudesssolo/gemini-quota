package dev.geminiquota.ui

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.geminiquota.GeminiQuotaApp
import dev.geminiquota.R
import dev.geminiquota.data.ClaudeConnectResult
import dev.geminiquota.data.ClaudeCookies
import dev.geminiquota.data.ConnectResult
import dev.geminiquota.data.CookieUtils
import dev.geminiquota.data.PasteRouting
import dev.geminiquota.data.ProviderId
import dev.geminiquota.data.QuotaPage
import dev.geminiquota.databinding.DialogCookiePasteBinding
import kotlinx.coroutines.launch

/**
 * Fallback for accounts that Google refuses to sign in inside a WebView.
 * Accepts the Cookie request header, a "Copy as cURL" command, or a JSON export,
 * for either provider: the paste is routed to Gemini or Claude by its content.
 */
object CookiePasteDialog {

    fun show(
        activity: AppCompatActivity,
        prefill: String? = null,
        onConnected: () -> Unit = {},
        onClaudeConnected: (QuotaPage) -> Unit = {},
    ) {
        val binding = DialogCookiePasteBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.paste_cookies_title)
            .setView(binding.root)
            .setPositiveButton(R.string.paste_connect_button, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        if (!prefill.isNullOrBlank()) binding.cookieInput.setText(prefill)
        binding.cookieInput.doAfterTextChanged { render(activity, binding, dialog) }
        binding.clipboardButton.setOnClickListener { pasteClipboard(activity, binding) }
        binding.helpButton.setOnClickListener { showHelp(activity) }
        binding.reportButton.setOnClickListener { copyReport(activity, binding) }
        render(activity, binding, dialog)

        dialog.setOnShowListener {
            render(activity, binding, dialog)
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val raw = binding.cookieInput.text?.toString().orEmpty()
                val repository = (activity.application as GeminiQuotaApp).repository
                binding.cookieLayout.error = null
                positive.isEnabled = false

                activity.lifecycleScope.launch {
                    when (PasteRouting.target(raw)) {
                        ProviderId.CLAUDE -> when (val result = repository.connectClaude(raw)) {
                            is ClaudeConnectResult.Success -> {
                                Toast.makeText(
                                    activity,
                                    claudeToast(activity, result.page),
                                    Toast.LENGTH_LONG,
                                ).show()
                                dialog.dismiss()
                                onClaudeConnected(result.page)
                            }

                            is ClaudeConnectResult.Failure -> {
                                positive.isEnabled = true
                                binding.cookieLayout.error = result.message
                                Toast.makeText(activity, R.string.connect_failed, Toast.LENGTH_LONG).show()
                            }
                        }

                        ProviderId.GEMINI -> when (val result = repository.connect(raw)) {
                            is ConnectResult.Success -> {
                                Toast.makeText(activity, R.string.connect_success, Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                onConnected()
                            }

                            is ConnectResult.Failure -> {
                                positive.isEnabled = true
                                binding.cookieLayout.error = result.message
                                Toast.makeText(activity, R.string.connect_failed, Toast.LENGTH_LONG).show()
                            }
                        }

                        null -> {
                            positive.isEnabled = true
                            binding.cookieLayout.error = activity.getString(R.string.connect_invalid)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Live feedback so a partial export is obvious before the request is sent. */
    private fun render(
        activity: AppCompatActivity,
        binding: DialogCookiePasteBinding,
        dialog: AlertDialog?,
    ) {
        val raw = binding.cookieInput.text?.toString()
        val target = PasteRouting.target(raw)

        // The button names the account it will link, so a Claude paste can never
        // be sent to the Gemini endpoint.
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setText(
                if (target == ProviderId.CLAUDE) R.string.paste_connect_claude
                else R.string.paste_connect_button,
            )
            isEnabled = target != null
        }

        binding.reportButton.isVisible = !raw.isNullOrBlank()

        if (target == ProviderId.CLAUDE) {
            binding.cookieStatus.setText(R.string.paste_status_claude)
            binding.cookieStatus.setTextColor(color(activity, R.color.accent))
            return
        }

        val summary = CookieUtils.summary(raw)
        when {
            summary.isEmpty -> {
                binding.cookieStatus.setText(R.string.paste_cookies_status_empty)
                binding.cookieStatus.setTextColor(color(activity, R.color.text_secondary))
            }

            summary.hasSession -> {
                binding.cookieStatus.text = activity.getString(
                    R.string.paste_cookies_status_ready,
                    summary.count,
                    summary.names.joinToString(", "),
                )
                binding.cookieStatus.setTextColor(color(activity, R.color.severity_ok))
            }

            else -> {
                // Name the cookies we did see: "it did not recognise my cookie"
                // is otherwise impossible to diagnose from the phone.
                val names = ClaudeCookies.cookieNames(raw)
                val site = ClaudeCookies.exportDomain(raw)
                binding.cookieStatus.text = when {
                    site != null && !site.contains("claude", true) &&
                        !site.contains("google", true) ->
                        activity.getString(R.string.paste_cookies_status_wrong_site, site)

                    names.isEmpty() ->
                        activity.getString(R.string.paste_cookies_status_no_session, summary.count)

                    else ->
                        activity.getString(
                            R.string.paste_cookies_status_unrecognized,
                            summary.count,
                            names.joinToString(", "),
                        )
                }
                binding.cookieStatus.setTextColor(color(activity, R.color.severity_danger))
            }
        }
    }

    /** `Claude connected · 5-hour 42% · Weekly 24%` — the only place it shows, for now. */
    private fun claudeToast(activity: AppCompatActivity, page: QuotaPage): CharSequence {
        val detail = if (page.windows.isEmpty()) {
            activity.getString(R.string.paste_no_windows)
        } else {
            QuotaPageFormat.summary(activity, page)
        }
        return activity.getString(R.string.claude_connect_success, detail)
    }

    /** Copies a value-free description of the paste, for support requests. */
    private fun copyReport(activity: AppCompatActivity, binding: DialogCookiePasteBinding) {
        val report = ClaudeCookies.diagnostics(binding.cookieInput.text?.toString())
        val clipboard = activity.getSystemService<android.content.ClipboardManager>()
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText("gemini-quota diag", report),
        )
        Toast.makeText(activity, R.string.paste_report_copied, Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipboard(activity: AppCompatActivity, binding: DialogCookiePasteBinding) {
        val copied = ClipboardCookies.read(activity)
        if (copied.isNullOrBlank()) {
            Toast.makeText(activity, R.string.paste_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.cookieLayout.error = null
        binding.cookieInput.setText(copied)
        binding.cookieInput.setSelection(binding.cookieInput.text?.length ?: 0)
    }

    fun showHelp(activity: AppCompatActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.paste_cookies_help_title)
            .setMessage(R.string.paste_cookies_help_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun color(activity: AppCompatActivity, resId: Int): Int =
        ContextCompat.getColor(activity, resId)
}
