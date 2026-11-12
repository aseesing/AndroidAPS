// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.pump.defs.PumpType

/**
 * How long a sensor or pod works, as the maker gives it: [lifetimeHours], then [graceHours] more
 * until it stops by itself.
 *
 * Only models whose lifetime is certain are listed. For everything else [of] gives null, and the
 * status lights count down to the user's own critical threshold as before.
 *
 * This is a personal addition, not in upstream AAPS. The table lives here, in its own file, and not
 * in the [SourceSensor] and [PumpType] enums, so that a new AAPS release merges without conflicts.
 */
class DeviceLifetime(val lifetimeHours: Int, val graceHours: Int) {

    companion object {

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
