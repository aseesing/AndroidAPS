// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear

import android.os.SystemClock
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventNsClientStatusUpdated
import app.aaps.plugins.sync.wear.WearResendSchedule.Kind
import app.aaps.plugins.sync.wear.WearResendSchedule.Outcome
import app.aaps.plugins.sync.wear.wearintegration.DataHandlerMobile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The events after which the watch gets everything again (a resend), and when: see [WearResendSchedule].
 * It replaces `WearPlugin.resendRequests` of upstream, which waits 2 s of quiet after every event.
 *
 * The sources are subscribed in [start], undispatched, so an event sent right after the start is not
 * lost (see `collectResilient`).
 */
@SingleIn(AppScope::class)
@Inject
class WearResendTiming(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val persistenceLayer: PersistenceLayer,
    private val eatReminder: EatReminder,
    private val iobCobCalculator: IobCobCalculator,
    private val config: Config,
    private val dataHandlerMobile: DataHandlerMobile
) {

    /**
     * Starts the resends in [scope], until it is cancelled. The timing runs on the steady clock: a wall
     * clock that is set back (by the user, or by the network time) must not hold a resend back.
     */
    fun start(scope: CoroutineScope) {
        start(scope, SystemClock::elapsedRealtime)
    }

    /**
     * [start] with the clock given, and the resend, for tests.
     *
     * @param now a steady clock in milliseconds, see [WearResendSchedule]
     * @param send the resend; says whether it really went out
     */
    internal fun start(
        scope: CoroutineScope,
        now: () -> Long,
        send: suspend (reasons: List<String>) -> Outcome = ::resendNow
    ): WearResendSchedule {
        val schedule = WearResendSchedule(now)
        fun forward(source: Flow<*>, reason: String, kind: Kind) {
            source.collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { schedule.request(reason, kind) }
        }
        // The calculation runs for a new BG, and again for a new TBR or a treatment
        rxBus.toFlow(EventAutosensCalculationFinished::class)
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) {
                schedule.calculationFinished(it.triggeredByNewBG, iobCobCalculator.ads.lastBg()?.timestamp)
            }
        forward(rxBus.toFlow(EventLoopUpdateGui::class), "EventLoopUpdateGui", Kind.CALCULATION_OR_LOOP)
        // AAPSCLIENT (only sent there): the new predictions, IOB and COB of the master arrive via NS
        // devicestatus, not a local loop run, so they count as one
        forward(rxBus.toFlow(EventNsClientStatusUpdated::class), "EventNsClientStatusUpdated", Kind.CALCULATION_OR_LOOP)
        // The database flows send only changes, no first value: nothing to drop. A sync can change many
        // rows one after the other: see the gap in WearResendSchedule
        forward(persistenceLayer.observeChanges<TT>(), "TempTargetChange", Kind.DATABASE_CHANGE)
        forward(persistenceLayer.observeChanges<RM>(), "RunningModeChange", Kind.DATABASE_CHANGE)
        forward(persistenceLayer.observeChanges<CA>(), "CarbsChange", Kind.DATABASE_CHANGE)
        forward(persistenceLayer.observeChanges<PS>(), "ProfileSwitchChange", Kind.DATABASE_CHANGE)
        forward(persistenceLayer.observeChanges<EPS>(), "EffectiveProfileSwitchChange", Kind.DATABASE_CHANGE)
        // An SMB is the loop's own: it comes with the loop result, so it may wait
        persistenceLayer.observeChanges<BS>()
            .collectResilient(scope, aapsLogger, LTag.WEAR, start = CoroutineStart.UNDISPATCHED) { boluses ->
                if (boluses.any { it.type != BS.Type.SMB }) schedule.request("BolusChange", Kind.DATABASE_CHANGE)
                else schedule.request("SmbChange", Kind.OTHER)
            }
        // The "time to eat" timer: the watch draws its end and rings at it. A StateFlow sends its current
        // value first, that is not a change.
        forward(eatReminder.state.drop(1), "EatReminderChange", Kind.USER_CHANGE)
        scope.launch {
            schedule.run(send) { reasons ->
                aapsLogger.error(LTag.WEAR, "Resend $reasons failed ${1 + WearResendSchedule.MAX_RETRIES} times, given up until a new reason comes")
            }
        }
        return schedule
    }

    /** One resend, and whether it went out. */
    private suspend fun resendNow(reasons: List<String>): Outcome {
        // resendData returns without sending anything then
        if (!config.appInitialized) {
            aapsLogger.debug(LTag.WEAR, "Resend $reasons waits: app not initialized")
            return Outcome.NOT_READY
        }
        return try {
            dataHandlerMobile.resendData(reasons.joinToString(","))
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Resend $reasons failed", e)
            Outcome.FAILED
        }
    }
}
