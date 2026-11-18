package app.aaps.core.interfaces.rx.weardata

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rule that keeps phone and watch on the same Custom watch face settings, and its message. */
class CwfSettingsMergeTest {

    private val any: (String, String) -> Boolean = { _, _ -> true }

    private fun v(value: String, at: Long) = CwfSettingValue(value, at)

    @Test
    fun aNewerValueIsTakenAndKeepsItsTime() {
        val result = CwfSettingsMerge.merge(mapOf("a" to v("true", 10)), mapOf("a" to v("false", 20)), localIsWatch = false, allowed = any)
        assertEquals(mapOf("a" to v("false", 20)), result.taken)
        assertEquals(mapOf("a" to v("false", 20)), result.merged)
        assertFalse(result.answer)
    }

    @Test
    fun anOlderValueIsNotTakenAndTheSenderGetsAnAnswer() {
        val result = CwfSettingsMerge.merge(mapOf("a" to v("true", 30)), mapOf("a" to v("false", 20)), localIsWatch = false, allowed = any)
        assertTrue(result.taken.isEmpty())
        assertEquals(mapOf("a" to v("true", 30)), result.merged)
        assertTrue(result.answer)
    }

    @Test
    fun theSameSettingsGiveNoAnswerSoNothingEchoes() {
        val same = mapOf("a" to v("true", 30), "b" to v("false", 5))
        val result = CwfSettingsMerge.merge(same, same, localIsWatch = false, allowed = any)
        assertTrue(result.taken.isEmpty())
        assertFalse(result.answer)
    }

    @Test
    fun aSettingTheSenderDoesNotHaveGivesAnAnswer() {
        val result = CwfSettingsMerge.merge(mapOf("a" to v("true", 0)), emptyMap(), localIsWatch = false, allowed = any)
        assertTrue(result.answer)
        // And the other way: an unknown setting is simply taken
        assertTrue(CwfSettingsMerge.merge(emptyMap(), mapOf("a" to v("true", 0)), localIsWatch = false, allowed = any).taken.containsKey("a"))
    }

    @Test
    fun aValueThatIsNotAllowedIsNeverTakenAndNeverStartsAnAnswer() {
        val allowed = { name: String, value: String -> name == "a" && value != "broken" }
        val result = CwfSettingsMerge.merge(
            local = mapOf("a" to v("true", 10), "old" to v("x", 10)),
            remote = mapOf("a" to v("broken", 99), "unknown" to v("1", 99)),
            localIsWatch = false,
            allowed = allowed
        )
        assertTrue(result.taken.isEmpty())
        // Our "a" counts against a remote "a" the rule cannot use; "old" is not ours to send
        assertTrue(result.answer)
        assertFalse(CwfSettingsMerge.merge(mapOf("old" to v("x", 10)), emptyMap(), localIsWatch = false, allowed = allowed).answer)
    }

    @Test
    fun equalTimesAreDecidedTheSameWayOnBothSides() {
        val phone = mapOf("a" to v("false", 50))
        val watch = mapOf("a" to v("true", 50))
        val onWatch = CwfSettingsMerge.merge(watch, phone, localIsWatch = true, allowed = any)
        val onPhone = CwfSettingsMerge.merge(phone, watch, localIsWatch = false, allowed = any)
        assertEquals(onPhone.merged, onWatch.merged)
    }

    @Test
    fun withBothTimesUnknownTheWatchWinsOnTheWatch() {
        // The phone has "true" with an unknown time, learned from another watch; this watch has "false"
        val onWatch = CwfSettingsMerge.merge(mapOf("a" to v("false", 0)), mapOf("a" to v("true", 0)), localIsWatch = true, allowed = any)
        assertTrue(onWatch.taken.isEmpty())
        assertEquals(mapOf("a" to v("false", 0)), onWatch.merged)
        assertTrue(onWatch.answer)
        // The same the other way round: "false" on the phone does not switch a "true" of the watch off
        val other = CwfSettingsMerge.merge(mapOf("a" to v("true", 0)), mapOf("a" to v("false", 0)), localIsWatch = true, allowed = any)
        assertTrue(other.taken.isEmpty())
        assertTrue(other.answer)
    }

    @Test
    fun withBothTimesUnknownTheWatchWinsOnThePhone() {
        val onPhone = CwfSettingsMerge.merge(mapOf("a" to v("true", 0)), mapOf("a" to v("false", 0)), localIsWatch = false, allowed = any)
        assertEquals(mapOf("a" to v("false", 0)), onPhone.taken)
        assertFalse(onPhone.answer)
        val other = CwfSettingsMerge.merge(mapOf("a" to v("false", 0)), mapOf("a" to v("true", 0)), localIsWatch = false, allowed = any)
        assertEquals(mapOf("a" to v("true", 0)), other.taken)
        assertFalse(other.answer)
    }

    @Test
    fun aKnownTimeStillWinsOverAnUnknownOne() {
        // A real change on the phone wins over a watch value with an unknown time, and the other way
        assertEquals(mapOf("a" to v("true", 10)), CwfSettingsMerge.merge(mapOf("a" to v("false", 0)), mapOf("a" to v("true", 10)), localIsWatch = true, allowed = any).taken)
        assertEquals(mapOf("a" to v("false", 10)), CwfSettingsMerge.merge(mapOf("a" to v("true", 0)), mapOf("a" to v("false", 10)), localIsWatch = false, allowed = any).taken)
    }

    @Test
    fun atFirstContactTheWatchKeepsItsValuesAndThePhoneTakesThem() {
        // A new watch with its own values meets a phone that holds other values, all with unknown times
        var phone = mapOf("a" to v("true", 0), "b" to v("false", 0), "c" to v("true", 0))
        var watch = mapOf("a" to v("false", 0), "b" to v("true", 0), "c" to v("true", 0), "d" to v("false", 0))
        val watchBefore = watch

        // The phone sends all it has, the watch merges and answers
        val onWatch = CwfSettingsMerge.merge(watch, phone, localIsWatch = true, allowed = any)
        watch = onWatch.merged
        assertTrue(onWatch.taken.isEmpty())
        assertTrue(onWatch.answer)
        assertEquals(watchBefore, watch)

        // The phone takes the values of the watch and does not answer
        val onPhone = CwfSettingsMerge.merge(phone, watch, localIsWatch = false, allowed = any)
        phone = onPhone.merged
        assertFalse(onPhone.answer)
        assertEquals(watchBefore, phone)

        // The next exchange moves nothing
        val again = CwfSettingsMerge.merge(watch, phone, localIsWatch = true, allowed = any)
        assertTrue(again.taken.isEmpty())
        assertFalse(again.answer)
    }

    @Test
    fun oneExchangeLeavesBothSidesEqualWithAtMostOneAnswer() {
        // Changed on the phone and, while not connected, on the watch: different settings
        var phone = mapOf("a" to v("false", 100), "b" to v("true", 0), "c" to v("true", 10))
        var watch = mapOf("a" to v("true", 0), "b" to v("false", 200), "d" to v("true", 0))

        // The phone sends, the watch merges and answers
        val onWatch = CwfSettingsMerge.merge(watch, phone, localIsWatch = true, allowed = any)
        watch = onWatch.merged
        assertTrue(onWatch.answer)
        // The phone merges the answer and would not answer again
        val onPhone = CwfSettingsMerge.merge(phone, watch, localIsWatch = false, allowed = any)
        phone = onPhone.merged
        assertFalse(onPhone.answer)

        assertEquals(watch, phone)
        assertEquals(mapOf("a" to v("false", 100), "b" to v("false", 200), "c" to v("true", 10), "d" to v("true", 0)), phone)
    }

    @Test
    fun aChangeIsNeverOlderThanTheValueItReplaces() {
        assertEquals(5001L, CwfSettingsMerge.changeTime(1000, v("true", 5000)))
        assertEquals(9000L, CwfSettingsMerge.changeTime(9000, v("true", 5000)))
        assertEquals(9000L, CwfSettingsMerge.changeTime(9000, null))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theMessageGoesThroughBothWireFormats() {
        val message = EventData.CwfSettingsSync(mapOf("show_iob" to v("false", 1234L), "dark" to v("true", 0L)), isAnswer = true)
        assertEquals(message, EventData.deserialize(message.serialize()))
        assertEquals(message, EventData.deserializeByte(message.serializeByte()))
        // An answer flag that is not there (an older sender) means no answer
        assertEquals(EventData.CwfSettingsSync(emptyMap(), isAnswer = false), EventData.deserialize(EventData.CwfSettingsSync(emptyMap()).serialize()))
    }

    @Test
    fun anUnknownMessageIsAnError() {
        // What an older app makes of a message it does not know
        val unknown = EventData.CwfSettingsSync(emptyMap()).serialize().replace("CwfSettingsSync", "SomethingNew")
        assertNull(EventData.deserialize(unknown) as? EventData.CwfSettingsSync)
        assertTrue(EventData.deserialize(unknown) is EventData.Error)
    }
}
