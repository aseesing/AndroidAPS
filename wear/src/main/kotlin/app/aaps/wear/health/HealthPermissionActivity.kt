package app.aaps.wear.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.aaps.core.interfaces.di.injectMetroMembers
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch

/**
 * Opened by a tap on the notification of [HeartRatePermissionNotifier]. Asks for the heart rate
 * permission, then updates the registration with Health Services. The face uses the heart rate from
 * the next batch of Health Services.
 */
class HealthPermissionActivity : ComponentActivity() {

    @Inject lateinit var registration: PassiveHealthRegistration
    @Inject lateinit var notifier: HeartRatePermissionNotifier

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { onAnswered() }

    override fun onCreate(savedInstanceState: Bundle?) {
        injectMetroMembers(this)
        super.onCreate(savedInstanceState)
        // After a recreate the answer to the first request still arrives, so do not ask twice
        if (savedInstanceState != null) return
        if (registration.isGranted()) onAnswered() else request.launch(HEART_RATE_PERMISSION)
    }

    private fun onAnswered() {
        lifecycleScope.launch {
            if (registration.isGranted()) notifier.cancel()
            registration.updateQuietly()
            finish()
        }
    }
}
