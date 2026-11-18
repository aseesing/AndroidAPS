package app.aaps.wear.health

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Builds the heart rate and steps classes once for the whole app. They take plain values, so tests can build them by hand. */
@ContributesTo(AppScope::class)
@BindingContainer
object HealthModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideHealthValues(context: Context): HealthValues =
        HealthValues(context.getSharedPreferences(HealthValues.FILE, Context.MODE_PRIVATE))

    @Provides
    @SingleIn(AppScope::class)
    fun providePassiveHealthRegistration(context: Context, healthValues: HealthValues, aapsLogger: AAPSLogger): PassiveHealthRegistration =
        PassiveHealthRegistration(
            client = HealthServicesPassiveClient(context),
            values = healthValues,
            aapsLogger = aapsLogger,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ) { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
}
