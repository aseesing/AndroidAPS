// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.daynight

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.DayNightRule
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.wear.keys.WearPlaceUseKey
import app.aaps.shared.impl.weardata.CwfPlaceNeeds
import app.aaps.shared.impl.weardata.CwfPlaceUse
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * When the phone may read its location for the watch, and what it sends. One switch for everything:
 * "Use the phone's location" of the "Day and night" screen.
 *
 * - NEEDED: the watch has a night face (its rule for when it is day, as upstream), or the main design
 *   needs the place, or a night design needs it for the weather (see [CwfPlaceNeeds]: the real sun and
 *   moon, the day look, the next sunrise or sunset, the weather, or a design that asks for it).
 * - ALLOWED: the switch is on and the location permission is granted
 *   ([WearDayNightSettings.usesPhoneLocation]).
 *
 * The location is read only when needed AND allowed. A chosen city is public data the user chose: it
 * is used when the location is not allowed or not known. Not needed: nothing is read, whatever the
 * switch says. Not allowed: nothing is read, whatever the design asks. The rule never goes into the
 * AAPSClient broadcast (`isForOwnWatchOnly`) and its `toString` shows no coordinates.
 *
 * The rule goes to the watch for a night face, or for a main design that needs the sun and the moon
 * or asks for the place ([CwfPlaceNeeds.needsRule]). A design that only shows the weather gets no
 * rule: the phone looks up the weather (see `WearWeather`) and sends only the weather.
 *
 * The phone learns what the designs need when the watch reports them (the main face in
 * `DataHandlerMobile`, the night face in `WearNightFace`), and keeps it, so it also knows it after a
 * restart. The watch keeps the rule by the same rule (`PlaceRuleKeeping` on the watch). When the watch
 * refuses the rule (it lost its design: reset, reinstalled or replaced), the phone forgets what it
 * kept, see [ruleRefused], so it stops reading the location for it.
 */
@SingleIn(AppScope::class)
@Inject
class WearPlaceUse(
    private val preferences: Preferences,
    private val settings: WearDayNightSettings,
    private val location: PhoneRoughLocation,
    private val carryOver: WearDayNightCarryOver,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) {

    /** The last rule message (a rule, or "no rule"), for the live preview, which must show what the watch shows. */
    @Volatile private var lastRule: EventData? = null

    /** When this process asked the watch for its design after the update, see [designRequest]; 0 when it did not. */
    @Volatile private var designAskedAt = 0L

    /** True once this process asked the watch for its design after a refused rule, see [ruleRefused]. */
    @Volatile private var askedAfterRefusal = false

    /** True once a design that cannot be read was logged in this process. */
    @Volatile private var unreadableLogged = false

    /** What the main design needs the place for. */
    fun mainUses(): Set<CwfPlaceUse> = usesOf(preferences.get(WearPlaceUseKey.MainDesign))

    /** What the night design needs the place for; empty without a night face. */
    fun nightUses(): Set<CwfPlaceUse> = usesOf(preferences.get(WearPlaceUseKey.NightDesign))

    /** True when the main design needs the rule on the watch, see [CwfPlaceNeeds.needsRule]. */
    fun mainNeedsRule(): Boolean = CwfPlaceNeeds.needsRule(mainUses())

    /** True when the watch needs the rule: with a night face on it, or a main design that needs it. */
    fun ruleNeeded(nightFaceOnWatch: Boolean): Boolean = nightFaceOnWatch || mainNeedsRule()

    /**
     * The rule for the watch, or null when nothing on the watch needs it. Reads the location only when
     * needed and allowed (see [WearDayNightSettings.rule]).
     */
    fun ruleMessage(nightFaceOnWatch: Boolean): EventData.ActionSetDayNightRule? =
        if (ruleNeeded(nightFaceOnWatch)) EventData.ActionSetDayNightRule(settings.rule()).also { lastRule = it } else null

    /** The last rule message the phone made, a rule or "no rule"; null before the first one. */
    fun lastRule(): EventData? = lastRule

    /**
     * The watch reported [cwf] as its main design. Remembers what it needs the place for. When that
     * changes whether the watch needs the rule (and it has no night face, which needs it anyway), the
     * watch gets the rule now, or "no rule".
     */
    fun mainDesignReported(cwf: CwfData, nightFaceOnWatch: Boolean) {
        val before = mainNeedsRule()
        val uses = usesOfDesign(cwf)
        store(WearPlaceUseKey.MainDesign, uses)
        carryOver.designKnown(uses.isNotEmpty())
        val after = CwfPlaceNeeds.needsRule(uses)
        if (nightFaceOnWatch || after == before) return
        val message = if (after) ruleMessage(nightFaceOnWatch = false)
        else EventData.ActionClearDayNightRule(dateUtil.now()).also { lastRule = it }
        message?.let { rxBus.send(EventMobileToWear(it)) }
    }

    /** The watch reported [cwf] as its night design, or null: it has none (any more). */
    fun nightDesignReported(cwf: CwfData?) {
        store(WearPlaceUseKey.NightDesign, cwf?.let { usesOfDesign(it) } ?: emptySet())
    }

    /**
     * The watch said it has no night face while the phone knew of none. The watch says so only when
     * it refused the rule: neither a night face nor its main design needs it (the watch was reset,
     * reinstalled or replaced, and has only the default design). So the phone forgets what the main
     * design needed, and reads and sends nothing more for it.
     *
     * In case the watch still has a design that needs the place (two answers crossed), the phone asks
     * it once in this process for its main design; its answer stores the need again. A watch with only
     * the default design does not answer. Once only, so a watch that keeps refusing (an older watch app)
     * cannot make the phone read the location again and again.
     *
     * @return the request for the main design, or null when there is nothing to forget or it was asked already
     */
    fun ruleRefused(): EventData? {
        if (mainUses().isEmpty()) return null
        store(WearPlaceUseKey.MainDesign, emptySet())
        aapsLogger.debug(LTag.WEAR, "Location: the watch has no design that needs the place, the phone forgets it")
        if (askedAfterRefusal) return null
        askedAfterRefusal = true
        return EventData.ActionrequestCustomWatchface(false, night = false)
    }

    /** True when a design on the watch shows the weather. */
    fun showsWeather(): Boolean = CwfPlaceUse.WEATHER in mainUses() || CwfPlaceUse.WEATHER in nightUses()

    /**
     * True when the weather may be looked up: a design shows it, and there can be a place for it (the
     * location is allowed, or a city is chosen). Reads no location.
     */
    fun weatherAllowed(): Boolean = showsWeather() && (settings.usesPhoneLocation() || cityPlace() != null)

    /**
     * The place to look up the weather for, or null. The phone's rough place only when a design shows
     * the weather and the location is allowed (only then it is read); else the chosen city.
     */
    fun weatherPlace(): DayNightRule.Place? {
        if (!showsWeather()) return null
        if (settings.usesPhoneLocation()) location.read()?.let { return it }
        return cityPlace()
    }

    /**
     * Once on this phone, when it never learned the main design (the first start after the update): the
     * request to the watch to send it, so a design that needs the place gets it soon, without waiting
     * for the Wear screen. Until the watch answers, the main design counts as needing nothing; a watch
     * with only the default design does not answer, and that design needs nothing. Null when known or
     * already asked.
     *
     * Called with every resend. The first resend at least [DESIGN_ROUND_MS] after the request (or in a
     * later process) ends the round: when no design came, the carry-over for the design is over (see
     * [WearDayNightCarryOver.designRoundOver]), so it cannot fire months later.
     */
    fun designRequest(nightFaceKnown: Boolean): EventData? {
        if (preferences.getIfExists(WearPlaceUseKey.MainDesign) != null) {
            if (carryOver.designPartOpen() && (designAskedAt == 0L || dateUtil.now() - designAskedAt >= DESIGN_ROUND_MS)) carryOver.designRoundOver()
            return null
        }
        preferences.put(WearPlaceUseKey.MainDesign, "")
        designAskedAt = dateUtil.now()
        return EventData.ActionrequestCustomWatchface(false, night = nightFaceKnown)
    }

    /** The chosen city as a place, when the choice below the switch is a known city. Reads no location. */
    private fun cityPlace(): DayNightRule.Place? =
        if (settings.fallbackSource() == DayNightSource.CITY) settings.city()?.let { DayNightRule.Place(it.latitude, it.longitude) } else null

    private fun store(key: WearPlaceUseKey, uses: Set<CwfPlaceUse>) {
        val text = uses.sortedBy { it.ordinal }.joinToString(SEPARATOR) { it.name }
        if (preferences.getIfExists(key) != text) preferences.put(key, text)
    }

    private fun usesOf(text: String): Set<CwfPlaceUse> =
        text.split(SEPARATOR).mapNotNull { name -> CwfPlaceUse.entries.firstOrNull { it.name == name } }.toSet()

    /** What [cwf] needs the place for. A json that cannot be read needs nothing; logged once, without its content. */
    private fun usesOfDesign(cwf: CwfData): Set<CwfPlaceUse> =
        CwfPlaceNeeds.of(cwf.json) {
            if (!unreadableLogged) {
                unreadableLogged = true
                aapsLogger.warn(LTag.WEAR, "Location: a design json could not be read, it counts as needing no place")
            }
        }

    companion object {

        private const val SEPARATOR = ","

        /** How long the watch has to answer the request for its design, see [designRequest]. */
        const val DESIGN_ROUND_MS = 60_000L
    }
}
