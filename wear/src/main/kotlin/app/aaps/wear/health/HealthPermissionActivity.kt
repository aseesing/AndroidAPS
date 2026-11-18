package app.aaps.wear.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.aaps.core.interfaces.di.injectMetroMembers
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch

/**
 * Opened by a tap on a heart rate or steps complication that has no permission yet. Asks for the
 * missing permissions of the tapped kind and of every kind that a watch face shows. After the answer
 * it updates the registration with Health Services and asks for new complication data, so a granted
 * complication stops showing "Allow" at once. Its value appears with the next batch from Health
 * Services.
 */
class HealthPermissionActivity : ComponentActivity() {

    @Inject lateinit var registration: PassiveHealthRegistration

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAnswered() }

    override fun onCreate(savedInstanceState: Bundle?) {
        injectMetroMembers(this)
        super.onCreate(savedInstanceState)
        // After a recreate the answer to the first request still arrives, so do not ask twice
        if (savedInstanceState != null) return
        val tapped = HealthKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) }
        val missing = registration.missingPermissions(tapped)
        if (missing.isEmpty()) onAnswered() else request.launch(missing.toTypedArray())
    }

    private fun onAnswered() {
        lifecycleScope.launch {
            registration.updateQuietly()
            requestHealthComplicationUpdates(applicationContext, HealthKind.entries.toSet())
            finish()
        }
    }

    companion object {

        /** The [HealthKind] name of the tapped complication. */
        const val EXTRA_KIND = "health_kind"
    }
}
