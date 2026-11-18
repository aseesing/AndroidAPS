// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.ui.compose.overview.statusLights

import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.CgmReportedSession

/** Where the sensor countdown starts, and the life of the sensor, as its glucose source reported them. */
internal class ReportedSensorLife(val startMs: Long, val lifetime: DeviceLifetime)

/**
 * The sensor countdown from the session the glucose source read from its own sensor (the Dexcom
 * ONE+ / G7 driver reads it from the sensor), or null. It comes before [DeviceLifetime.forSensor]:
 * the sensor knows its own end better than a table or the life the user set. The watch counts to
 * the same end, see [CgmReportedSession].
 *
 * Null, and so the row is as without this, when [countdown] is off (the user wants the age), or when
 * the source reports nothing it can be sure of ([CgmReportedSession.of] says when).
 *
 * @param sensorChangeMs the time of the newest sensor change therapy event, or null.
 */
internal fun reportedSensorLife(bgSource: BgSource, sensorChangeMs: Long?, countdown: Boolean): ReportedSensorLife? {
    if (!countdown) return null
    val session = CgmReportedSession.of(bgSource, sensorChangeMs) ?: return null
    return ReportedSensorLife(
        startMs = session.startMs,
        lifetime = DeviceLifetime(lifetimeHours = session.lifetimeHours, graceHours = session.graceHours),
    )
}
