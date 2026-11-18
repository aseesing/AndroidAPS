package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.pump.defs.PumpType

/**
 * How long a sensor or pod works, as the maker gives it: [lifetimeHours], then [graceHours] more
 * until it stops by itself.
 *
 * Only models whose lifetime is certain are listed. For everything else [of] gives null, and the
 * status lights show the age of the part, as without the countdown. A wrong countdown is worse than
 * an age, so a model is only added when its end is known for sure. For a sensor the user can set the
 * life, see [forSensor].
 */
class DeviceLifetime(val lifetimeHours: Int, val graceHours: Int) {

    companion object {

        /**
         * The life of the sensor of the newest BG reading, in this order: the life this code knows for
         * the model ([of]), else [userLifetimeHours], the life the user set (0 means not set), else null.
         * A life the user set has no grace period.
         */
        fun forSensor(sensor: SourceSensor?, userLifetimeHours: Int): DeviceLifetime? =
            // A glucose source that reports the session length of its own sensor can be asked first here later.
            of(sensor)
                ?: userLifetimeHours.takeIf { it > 0 }?.let { DeviceLifetime(lifetimeHours = it, graceHours = 0) }

        /** The sensor of the newest BG reading, only the models whose life is known for sure. */
        fun of(sensor: SourceSensor?): DeviceLifetime? = when (sensor) {
            SourceSensor.DEXCOM_G6 -> DeviceLifetime(lifetimeHours = 10 * 24, graceHours = 0)
            // Not the Dexcom G7: the 10 day and the 15 day G7 send the same label, so the reading does
            // not say when the sensor ends. It can be added when a source reports the session length.
            else                   -> null
        }

        /** The active pump. A pod also holds the insulin, so its end is the end of the insulin too. */
        fun of(pump: PumpType?): DeviceLifetime? = when (pump) {
            // 3 days, then 8 more hours until the pod stops itself: NOMINAL_POD_LIFE and
            // SERVICE_DURATION in the Eros driver.
            PumpType.OMNIPOD_EROS, PumpType.OMNIPOD_DASH -> DeviceLifetime(lifetimeHours = 72, graceHours = 8)
            else                                         -> null
        }
    }
}
