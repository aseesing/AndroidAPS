package app.aaps.plugins.sync.wear.daynight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.math.roundToInt

/** The phone's rough place, for day and night on the watch. */
interface PhoneRoughLocation {

    /** True when the app may read the location (coarse or fine). */
    fun hasPermission(): Boolean

    /**
     * The newest place the phone still knows, rounded to 0.1 degree (about 10 km). Null without
     * permission, without a known place, or when the newest place is older than [MAX_AGE_MILLIS]: a
     * place from an old trip must not set day and night. Starts no location request.
     */
    fun read(): DayNightRule.Place?

    companion object {

        /** The permission asked for: an approximate place is enough. */
        const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

        /** A known place older than this (one day) is not used. */
        const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

        /** [latitude] and [longitude] rounded to 0.1 degree. */
        fun rounded(latitude: Double, longitude: Double) =
            DayNightRule.Place((latitude * 10).roundToInt() / 10.0, (longitude * 10).roundToInt() / 10.0)
    }
}

/**
 * [PhoneRoughLocation] from the last known place of the phone's location providers. The permission
 * is the one the app already declares for the automation plugin; no new permission is added.
 */
@ContributesBinding(AppScope::class)
@Inject
class AndroidPhoneRoughLocation(private val context: Context, private val dateUtil: DateUtil) : PhoneRoughLocation {

    override fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    override fun read(): DayNightRule.Place? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val newest = manager.getProviders(false).mapNotNull { lastKnown(manager, it) }.maxByOrNull { it.time } ?: return null
        if (dateUtil.now() - newest.time > PhoneRoughLocation.MAX_AGE_MILLIS) return null
        return PhoneRoughLocation.rounded(newest.latitude, newest.longitude)
    }

    /** The last place of one provider. A provider that may not be read is skipped, the others still count. */
    @SuppressLint("MissingPermission") // checked in read(), and a SecurityException is caught
    private fun lastKnown(manager: LocationManager, provider: String): Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
