// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.daynight

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
 * The phone learns what the designs need when the watch reports them (the main face in
 * `DataHandlerMobile`, the night face in `WearNightFace`), and keeps it, so it also knows it after a
 * restart. The watch keeps the rule by the same rule (`PlaceRuleKeeping` on the watch).
 */
@SingleIn(AppScope::class)
@Inject
class WearPlaceUse(
    private val preferences: Preferences,
    private val settings: WearDayNightSettings,
    private val location: PhoneRoughLocation,
    private val carryOver: WearDayNightCarryOver,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil
) {

    /** The last rule message (a rule, or "no rule"), for the live preview, which must show what the watch shows. */
    @Volatile private var lastRule: EventData? = null

    /** What the main design needs the place for. */
    fun mainUses(): Set<CwfPlaceUse> = usesOf(preferences.get(WearPlaceUseKey.MainDesign))

    /** What the night design needs the place for; empty without a night face. */
    fun nightUses(): Set<CwfPlaceUse> = usesOf(preferences.get(WearPlaceUseKey.NightDesign))

    /** True when the main design needs the place. */
    fun mainNeedsPlace(): Boolean = mainUses().isNotEmpty()

    /** True when the watch needs the rule: with a night face on it, or a main design that needs the place. */
    fun ruleNeeded(nightFaceOnWatch: Boolean): Boolean = nightFaceOnWatch || mainNeedsPlace()

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
        val before = mainNeedsPlace()
        val uses = CwfPlaceNeeds.of(cwf.json)
        store(WearPlaceUseKey.MainDesign, uses)
        carryOver.designKnown(uses.isNotEmpty())
        if (nightFaceOnWatch || uses.isNotEmpty() == before) return
        val message = if (uses.isNotEmpty()) ruleMessage(nightFaceOnWatch = false)
        else EventData.ActionClearDayNightRule(dateUtil.now()).also { lastRule = it }
        message?.let { rxBus.send(EventMobileToWear(it)) }
    }

    /** The watch reported [cwf] as its night design, or null: it has none (any more). */
    fun nightDesignReported(cwf: CwfData?) {
        store(WearPlaceUseKey.NightDesign, CwfPlaceNeeds.of(cwf?.json))
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
     */
    fun designRequest(nightFaceKnown: Boolean): EventData? {
        if (preferences.getIfExists(WearPlaceUseKey.MainDesign) != null) return null
        preferences.put(WearPlaceUseKey.MainDesign, "")
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

    private companion object {

        const val SEPARATOR = ","
    }
}
