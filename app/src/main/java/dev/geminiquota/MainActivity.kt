package dev.geminiquota

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.geminiquota.data.UsageUiState
import dev.geminiquota.databinding.ActivityMainBinding
import dev.geminiquota.ui.ClipboardCookies
import dev.geminiquota.ui.CookiePasteDialog
import dev.geminiquota.ui.MainRenderer
import dev.geminiquota.ui.applySystemBarInsets
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: MainRenderer
    private var lastState: UsageUiState? = null

    private val repository get() = (application as GeminiQuotaApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.dashboard_title)

        renderer = MainRenderer(this, binding)

        binding.connectButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        binding.pasteButton.setOnClickListener {
            CookiePasteDialog.show(this, prefill = ClipboardCookies.read(this))
        }
        binding.openUsageButton.setOnClickListener { openUsagePage() }
        binding.errorDetailsButton.setOnClickListener { showErrorDetails() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.state.collect { state ->
                    lastState = state
                    renderer.render(state)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        lifecycleScope.launch { repository.refreshIfStale() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val signedIn = repository.currentState().isSignedIn
        menu.findItem(R.id.action_refresh)?.isVisible = signedIn
        menu.findItem(R.id.action_sign_out)?.isVisible = signedIn
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            lifecycleScope.launch { repository.refresh() }
            true
        }

        R.id.action_import -> {
            // Also the entry point for refreshing an expired session without
            // signing out, and for testing a claude.ai cookie.
            CookiePasteDialog.show(this, prefill = ClipboardCookies.read(this))
            true
        }

        R.id.action_sign_out -> {
            confirmSignOut()
            true
        }

        R.id.action_about -> {
            showAbout()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun openUsagePage() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/usage")),
            )
        }.onFailure {
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_body)
            .setPositiveButton(R.string.sign_out_confirm) { _, _ ->
                lifecycleScope.launch {
                    repository.signOut()
                    clearWebViewCookies()
                    invalidateOptionsMenu()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearWebViewCookies() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies { manager.flush() }
        manager.flush()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showErrorDetails() {
        val state = lastState
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_details_title)
            .setMessage(
                state?.errorDetail?.takeIf { it.isNotBlank() }
                    ?: state?.errorMessage
                    ?: getString(R.string.error_unknown),
            )
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
