package dev.geminiquota.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.geminiquota.GeminiQuotaApp
import dev.geminiquota.R
import dev.geminiquota.data.ConnectResult
import dev.geminiquota.data.CookieUtils
import dev.geminiquota.databinding.DialogCookiePasteBinding
import kotlinx.coroutines.launch

/**
 * Fallback for accounts that Google refuses to sign in inside a WebView.
 * Accepts the Cookie request header, a "Copy as cURL" command, or a JSON export.
 */
object CookiePasteDialog {

    fun show(activity: AppCompatActivity, onConnected: () -> Unit = {}) {
        val binding = DialogCookiePasteBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.paste_cookies_title)
            .setView(binding.root)
            .setPositiveButton(R.string.connect_button, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        binding.cookieInput.doAfterTextChanged { render(activity, binding) }
        binding.helpButton.setOnClickListener { showHelp(activity) }
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
        val summary = CookieUtils.summary(binding.cookieInput.text?.toString())
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
                binding.cookieStatus.text = activity.getString(
                    R.string.paste_cookies_status_no_session,
                    summary.count,
                )
                binding.cookieStatus.setTextColor(color(activity, R.color.severity_danger))
            }
        }
    }

    private fun showHelp(activity: AppCompatActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.paste_cookies_help_title)
            .setMessage(R.string.paste_cookies_help_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun color(activity: AppCompatActivity, resId: Int): Int =
        ContextCompat.getColor(activity, resId)
}
