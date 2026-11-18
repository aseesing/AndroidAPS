// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.core.data.model

import app.aaps.core.data.pump.defs.PumpType

/**
 * How long a sensor or pod works, as the maker gives it: [lifetimeHours], then [graceHours] more
 * until it stops by itself.
 *
 * Only models whose lifetime is certain are listed. For everything else [of] gives null, and the
 * watch counts down to the user's own critical threshold.
 *
 * Only the watch uses this table (the sensor and pump hands of the D2 face, through `WearPartTimes`
 * in `:plugins:sync`). The status lights on the phone use the upstream table
 * `app.aaps.ui.compose.overview.statusLights.DeviceLifetime` in `:ui`, which `:plugins:sync` cannot
 * reach. The two differ on purpose: this one still counts a Dexcom G7 as 10 days + 12 h, as the watch
 * always did, while the upstream one leaves the G7 out (a 15 day G7 sends the same label) and uses the
 * "Sensor life" setting instead. The G6 and pod entries are the same in both: change them together.
 *
 * Both come after the session a glucose source read from its own sensor (the Dexcom ONE+ / G7
 * driver), `CgmReportedSession` in `:core:interfaces`: when there is one, the phone and the watch
 * count to that same end, and this table is not used for the sensor.
 *
 * This is a personal addition, not in upstream AAPS. The table lives here, in its own file, and not
 * in the [SourceSensor] and [PumpType] enums, so that a new AAPS release merges without conflicts.
 */
class DeviceLifetime(val lifetimeHours: Int, val graceHours: Int) {

    companion object {

        private const val HOUR_MS = 60 * 60 * 1000L

        /**
         * When a part that was changed at [changedAt] stops: after its [lifetime] and grace period when they
         * are known, else after [fallbackHours], the user's own critical threshold.
         */
        fun endOf(changedAt: Long, lifetime: DeviceLifetime?, fallbackHours: Int): Long =
            changedAt + (lifetime?.let { it.lifetimeHours + it.graceHours } ?: fallbackHours) * HOUR_MS

        /** The sensor of the newest BG reading. */
        fun of(sensor: SourceSensor?): DeviceLifetime? = when (sensor) {
            SourceSensor.DEXCOM_G6 -> DeviceLifetime(lifetimeHours = 10 * 24, graceHours = 0)
            // A 15 day G7 sends the same label, so it would count down 5 days too early. The reading
            // does not tell the two apart.
            SourceSensor.DEXCOM_G7 -> DeviceLifetime(lifetimeHours = 10 * 24, graceHours = 12)
            else                   -> null
        }

        /** The active pump. A pod also holds the insulin, so its end is the end of the insulin too. */
        fun of(pump: PumpType?): DeviceLifetime? = when (pump) {
            // 3 days, then 8 more hours until the pod stops itself. The DASH driver uses the same
            // 8 hours for its own expiry alerts.
            PumpType.OMNIPOD_EROS, PumpType.OMNIPOD_DASH -> DeviceLifetime(lifetimeHours = 72, graceHours = 8)
            else                                         -> null
        }
    }
}
