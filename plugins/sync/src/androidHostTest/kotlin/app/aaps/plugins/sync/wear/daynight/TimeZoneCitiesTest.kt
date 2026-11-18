package app.aaps.plugins.sync.wear.daynight

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.util.TimeZone

class TimeZoneCitiesTest {

    private val sut = TimeZoneCities()

    @Test
    fun `every row of the table parses`() {
        val rows = TIME_ZONE_TABLE.lines().filter { it.isNotBlank() }
        assertThat(sut.all).hasSize(rows.size)
        assertThat(sut.all.size).isAtLeast(400)
    }

    @Test
    fun `coordinates are in range and country codes are two capital letters`() {
        for (city in sut.all) {
            assertThat(city.latitude).isAtLeast(-90.0)
            assertThat(city.latitude).isAtMost(90.0)
            assertThat(city.longitude).isAtLeast(-180.0)
            assertThat(city.longitude).isAtMost(180.0)
            assertThat(city.countryCode).matches("[A-Z]{2}")
        }
    }

    @Test
    fun `zone ids are unique and real time zones`() {
        val ids = sut.all.map { it.zoneId }
        assertThat(ids).containsNoDuplicates()
        val known = ZoneId.getAvailableZoneIds()
        // A very new zone may be missing from an older Java; nearly all must be known
        assertThat(ids.count { it in known }).isAtLeast(ids.size - 5)
    }

    @Test
    fun `cities are found by zone and by country`() {
        val amsterdam = sut.byZoneId("Europe/Amsterdam")
        assertThat(amsterdam?.countryCode).isEqualTo("NL")
        assertThat(amsterdam!!.latitude).isWithin(0.01).of(52.37)
        assertThat(amsterdam.longitude).isWithin(0.01).of(4.90)
        assertThat(sut.inCountry("AU").map { it.zoneId }).contains("Australia/Sydney")
        assertThat(sut.countryCodes()).containsAtLeast("NL", "US", "IN", "NZ")
        assertThat(sut.byZoneId("Nowhere/Atlantis")).isNull()
    }

    @Test
    fun `the phone's zone is found under an older name too`() {
        // Some phones still say Asia/Calcutta; the table has Asia/Kolkata
        assertThat(sut.firstKnown(listOf("Asia/Calcutta", "Asia/Kolkata"))?.zoneId).isEqualTo("Asia/Kolkata")
    }

    @Test
    fun `the default zone maps to a city, or to no suggestion`() {
        val id = TimeZone.getDefault().id
        val city = sut.firstKnown(listOf(id))
        // A zone without a city (UTC, Etc/GMT+1) gives no suggestion: the list then starts with the countries
        if (city == null) assertThat(id).matches("UTC|GMT|Etc/.*")
        else assertThat(city.zoneId).isEqualTo(id)
        assertThat(sut.firstKnown(listOf("UTC", "Etc/UTC"))).isNull()
    }

    @Test
    fun `a bad row is refused`() {
        assertThrows<IllegalArgumentException> { TimeZoneCities.parse("Europe/Amsterdam NL 52.37") }
        assertThrows<NumberFormatException> { TimeZoneCities.parse("Europe/Amsterdam NL north 4.9") }
    }
}
