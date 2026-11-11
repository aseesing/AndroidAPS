package app.aaps.wear.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Opened by a tap on a heart rate or steps complication that has no permission yet. Asks for both
 * permissions at once, then registers with Health Services and refreshes the complications, so the
 * values appear without anything else to do.
 */
class HealthPermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch {
            PassiveHealthRegistration.updateQuietly(applicationContext)
            requestHealthComplicationUpdates(applicationContext, HealthKind.entries.toSet())
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request.launch(HealthKind.entries.map { it.permission }.toTypedArray())
    }
}
