package dev.geminiquota

import android.app.Application
import dev.geminiquota.data.SessionStore
import dev.geminiquota.data.UsageRepository
import dev.geminiquota.widget.QuotaWidgetRenderer
import dev.geminiquota.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GeminiQuotaApp : Application() {

    lateinit var repository: UsageRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        repository = UsageRepository(this, SessionStore(this))

        // Every repository change (refresh start/end, sign-in, error) re-renders
        // the widget, so background work and the app stay in sync.
        scope.launch {
            repository.state.collect { state ->
                QuotaWidgetRenderer.updateAll(this@GeminiQuotaApp, state)
            }
        }

        if (repository.currentState().isSignedIn) {
            RefreshScheduler.ensurePeriodic(this)
        }
    }
}
