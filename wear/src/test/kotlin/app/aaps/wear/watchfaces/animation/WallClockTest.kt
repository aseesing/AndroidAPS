// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** [WallClock] gives the very same `sec`, `min` and `hour` as `LocalTime` of the zone. */
class WallClockTest {

    private fun assertSame(clock: WallClock, zone: ZoneId, epochMs: Long) {
        clock.set(epochMs)
        val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        val at = "$epochMs in $zone"
        assertWithMessage("second, $at").that(clock.second).isEqualTo(time.second + time.nano / 1e9)
        assertWithMessage("minute, $at").that(clock.minute).isEqualTo(time.minute.toDouble())
        assertWithMessage("hour, $at").that(clock.hour).isEqualTo(time.hour.toDouble())
    }

    @Test
    fun theSameTimeOfDayAsLocalTime() {
        val random = Random(7)
        for (zone in listOf("UTC", "Europe/Amsterdam", "America/St_Johns", "Asia/Kathmandu", "Australia/Lord_Howe").map { ZoneId.of(it) }) {
            val clock = WallClock().apply { this.zone = zone }
            // Forward and back, over years, and before 1970
            repeat(2000) { assertSame(clock, zone, random.nextLong(-2_000_000_000_000L, 4_000_000_000_000L)) }
        }
    }

    @Test
    fun overTheChangeToSummerTimeAndBack() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val clock = WallClock().apply { this.zone = zone }
        for (change in listOf(ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, ZoneId.of("UTC")), ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, ZoneId.of("UTC")))) {
            val at = change.toInstant().toEpochMilli()
            // Every 250 ms from two seconds before to two seconds after, then a few going back
            for (ms in at - 2000..at + 2000 step 250) assertSame(clock, zone, ms)
            for (ms in listOf(at, at - 1, at - 3_600_000, at + 1)) assertSame(clock, zone, ms)
        }
    }

    @Test
    fun anotherZoneIsFollowed() {
        val clock = WallClock()
        val now = 1_768_472_430_500L
        clock.zone = ZoneId.of("UTC")
        assertSame(clock, ZoneId.of("UTC"), now)
        clock.zone = ZoneId.of("Asia/Tokyo")
        assertSame(clock, ZoneId.of("Asia/Tokyo"), now)
    }
}
