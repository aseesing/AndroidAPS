// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.DeviceLifetime
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.source.CgmReportedSession
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * When the sensor and the pump part (the pod, or the cannula of another pump) stop, for the watch: the
 * real end of a known model, grace period included ([DeviceLifetime]), else the user's critical
 * threshold. The status lights on the phone have their own rule (a switch and a "Sensor life"
 * setting), see [DeviceLifetime] for how the two differ.
 *
 * For the sensor, the session the glucose source read from the sensor itself comes first
 * ([CgmReportedSession]). The status lights on the phone count to the same end with it.
 */
class WearPartTimes(
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences
) {

    /**
     * When the sensor stops, in ms: the end the glucose source reported, else from the model of the
     * newest reading. Null when neither the source nor a sensor change tells when the sensor started.
     */
    suspend fun sensorEnd(): Long? {
        val change = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
        CgmReportedSession.of(activePlugin.activeBgSource, change?.timestamp)?.let { return it.endMs }
        change ?: return null
        val model = persistenceLayer.getLastGlucoseValue()?.sourceSensor
        return DeviceLifetime.endOf(change.timestamp, DeviceLifetime.of(model), preferences.get(IntKey.OverviewSageCritical))
    }

    /** When the pod, or the cannula of another pump, stops, in ms: the time of the active pump. Null when no change is known. */
    suspend fun pumpEnd(): Long? {
        val change = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) ?: return null
        return DeviceLifetime.endOf(change.timestamp, DeviceLifetime.of(activePlugin.activePump.model()), preferences.get(IntKey.OverviewCageCritical))
    }
}
