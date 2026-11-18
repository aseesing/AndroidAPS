package app.aaps.plugins.sync.wear.daynight

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The phone's last known place, read through the real [LocationManager] of Robolectric, with a fixed
 * time from [DateUtil].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPhoneRoughLocationTest {

    private val application: Application = RuntimeEnvironment.getApplication()
    private val locationManager: LocationManager = application.getSystemService(LocationManager::class.java)
    private val dateUtil: DateUtil = mock()
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private lateinit var sut: AndroidPhoneRoughLocation

    @Before
    fun setUp() {
        whenever(dateUtil.now()).thenReturn(now)
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        sut = AndroidPhoneRoughLocation(application, dateUtil)
    }

    private fun known(provider: String, ageMillis: Long, latitude: Double, longitude: Double) {
        val location = Location(provider).apply {
            this.latitude = latitude
            this.longitude = longitude
            time = now - ageMillis
        }
        shadowOf(locationManager).setLastKnownLocation(provider, location)
    }

    @Test
    fun `a place from the last day is used, rounded`() {
        known(LocationManager.NETWORK_PROVIDER, ageMillis = 2 * hour, latitude = 52.3731, longitude = 4.8922)

        assertThat(sut.read()).isEqualTo(DayNightRule.Place(52.4, 4.9))
    }

    @Test
    fun `a place of exactly one day is still used`() {
        known(LocationManager.NETWORK_PROVIDER, ageMillis = PhoneRoughLocation.MAX_AGE_MILLIS, latitude = 52.3731, longitude = 4.8922)

        assertThat(sut.read()).isEqualTo(DayNightRule.Place(52.4, 4.9))
    }

    @Test
    fun `a place older than one day is not used`() {
        known(LocationManager.NETWORK_PROVIDER, ageMillis = 25 * hour, latitude = 52.3731, longitude = 4.8922)

        assertThat(sut.read()).isNull()
    }

    @Test
    fun `the newest place of all providers counts`() {
        known(LocationManager.GPS_PROVIDER, ageMillis = 30 * hour, latitude = -33.8688, longitude = 151.2093)
        known(LocationManager.NETWORK_PROVIDER, ageMillis = hour, latitude = 52.3731, longitude = 4.8922)

        assertThat(sut.read()).isEqualTo(DayNightRule.Place(52.4, 4.9))
    }

    @Test
    fun `without a known place nothing is used`() {
        assertThat(sut.read()).isNull()
    }

    @Test
    fun `without the permission no place is read`() {
        known(LocationManager.NETWORK_PROVIDER, ageMillis = hour, latitude = 52.3731, longitude = 4.8922)
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertThat(sut.hasPermission()).isFalse()
        assertThat(sut.read()).isNull()
    }
}
