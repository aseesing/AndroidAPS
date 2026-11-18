// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.DeviceLifetime
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * When the sensor and the pump part (the pod, or the cannula of another pump) stop, for the watch: the
 * real end of a known model, grace period included ([DeviceLifetime]), else the user's critical
 * threshold. The status lights on the phone have their own rule (a switch and a "Sensor life"
 * setting), see [DeviceLifetime] for how the two differ.
 */
class WearPartTimes(
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences
) {

    /** When the sensor stops, in ms: its model comes from the newest reading. Null when no sensor change is known. */
    suspend fun sensorEnd(): Long? {
        val change = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE) ?: return null
        val model = persistenceLayer.getLastGlucoseValue()?.sourceSensor
        return DeviceLifetime.endOf(change.timestamp, DeviceLifetime.of(model), preferences.get(IntKey.OverviewSageCritical))
    }

    /** When the pod, or the cannula of another pump, stops, in ms: the time of the active pump. Null when no change is known. */
    suspend fun pumpEnd(): Long? {
        val change = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE) ?: return null
        return DeviceLifetime.endOf(change.timestamp, DeviceLifetime.of(activePlugin.activePump.model()), preferences.get(IntKey.OverviewCageCritical))
    }
}
