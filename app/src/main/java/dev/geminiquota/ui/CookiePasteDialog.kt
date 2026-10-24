package dev.geminiquota.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.geminiquota.GeminiQuotaApp
import dev.geminiquota.R
import dev.geminiquota.data.ClaudeCookies
import dev.geminiquota.data.ClaudeUsageClient
import dev.geminiquota.data.ConnectResult
import dev.geminiquota.data.CookieUtils
import dev.geminiquota.databinding.DialogCookiePasteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fallback for accounts that Google refuses to sign in inside a WebView.
 * Accepts the Cookie request header, a "Copy as cURL" command, or a JSON export.
 */
object CookiePasteDialog {

    fun show(
        activity: AppCompatActivity,
        prefill: String? = null,
        onConnected: () -> Unit = {},
    ) {
        val binding = DialogCookiePasteBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.paste_cookies_title)
            .setView(binding.root)
            .setPositiveButton(R.string.connect_button, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        if (!prefill.isNullOrBlank()) binding.cookieInput.setText(prefill)
        binding.cookieInput.doAfterTextChanged { render(activity, binding) }
        binding.clipboardButton.setOnClickListener { pasteClipboard(activity, binding) }
        binding.helpButton.setOnClickListener { showHelp(activity) }
        binding.probeButton.setOnClickListener { probeClaude(activity, binding) }
        render(activity, binding)

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val raw = binding.cookieInput.text?.toString().orEmpty()
                if (!CookieUtils.summary(raw).hasSession) {
                    binding.cookieLayout.error = activity.getString(R.string.connect_invalid)
                    return@setOnClickListener
                }

                binding.cookieLayout.error = null
                positive.isEnabled = false
                val repository = (activity.application as GeminiQuotaApp).repository
                activity.lifecycleScope.launch {
                    when (val result = repository.connect(raw)) {
                        is ConnectResult.Success -> {
                            Toast.makeText(activity, R.string.connect_success, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onConnected()
                        }

                        is ConnectResult.Failure -> {
                            binding.cookieLayout.error = result.message
                            positive.isEnabled = true
                            Toast.makeText(activity, R.string.connect_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Live feedback so a partial export is obvious before the request is sent. */
    private fun render(activity: AppCompatActivity, binding: DialogCookiePasteBinding) {
        val raw = binding.cookieInput.text?.toString()

        // The Claude entry point must be visible before pasting, otherwise the
        // dialog looks like it only understands Gemini.
        binding.probeButton.isVisible = true

        if (ClaudeCookies.looksLikeClaudeSession(raw)) {
            binding.cookieStatus.setText(R.string.paste_probe_detected)
            binding.cookieStatus.setTextColor(color(activity, R.color.accent))
            binding.probeButton.isEnabled = true
            return
        }
        binding.probeButton.isEnabled = false

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

    /**
     * Read-only check of a claude.ai session: one discovery call plus one usage
     * call, printing only window names and percentages. Nothing is stored, so
     * this is safe to run before Claude support is wired in.
     */
    private fun probeClaude(activity: AppCompatActivity, binding: DialogCookiePasteBinding) {
        val raw = binding.cookieInput.text?.toString().orEmpty()
        binding.probeButton.isEnabled = false
        binding.cookieStatus.setText(R.string.paste_probe_running)
        binding.cookieStatus.setTextColor(color(activity, R.color.text_secondary))

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ClaudeUsageClient().fetchUsage(raw) }
            }
            binding.probeButton.isEnabled = true
            result.fold(
                onSuccess = { page ->
                    // A plan can answer with no quota windows at all; that is a
                    // successful probe, not an empty result.
                    val detail = if (page.windows.isEmpty()) {
                        activity.getString(R.string.paste_probe_no_windows)
                    } else {
                        QuotaPageFormat.summary(activity, page)
                    }
                    binding.cookieStatus.text = activity.getString(
                        R.string.paste_probe_ok,
                        detail,
                    )
                    binding.cookieStatus.setTextColor(color(activity, R.color.severity_ok))
                },
                onFailure = { error ->
                    binding.cookieStatus.text = activity.getString(
                        R.string.paste_probe_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                    binding.cookieStatus.setTextColor(color(activity, R.color.severity_danger))
                },
            )
        }
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
