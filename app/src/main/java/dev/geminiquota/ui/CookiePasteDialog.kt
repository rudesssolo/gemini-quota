package dev.geminiquota.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 * Users can paste the Cookie request header exported from their browser.
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

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val raw = binding.cookieInput.text?.toString().orEmpty()
                if (!CookieUtils.looksLikeGeminiSession(raw)) {
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
}
