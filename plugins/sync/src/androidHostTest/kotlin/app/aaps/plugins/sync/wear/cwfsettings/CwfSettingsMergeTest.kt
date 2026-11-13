// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.cwfsettings

import app.aaps.core.interfaces.rx.weardata.CwfSettingValue
import app.aaps.core.interfaces.rx.weardata.CwfSettingsMerge
import app.aaps.core.interfaces.rx.weardata.CwfSettingsSync
import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Test

/** The rule that keeps phone and watch on the same Custom watch face settings, and its message. */
class CwfSettingsMergeTest {

    private val any: (String, String) -> Boolean = { _, _ -> true }

    private fun v(value: String, at: Long) = CwfSettingValue(value, at)

    @Test
    fun aNewerValueIsTakenAndKeepsItsTime() {
        val result = CwfSettingsMerge.merge(mapOf("a" to v("true", 10)), mapOf("a" to v("false", 20)), any)
        assertThat(result.taken).containsExactly("a", v("false", 20))
        assertThat(result.merged).containsExactly("a", v("false", 20))
        assertThat(result.answer).isFalse()
    }

    @Test
    fun anOlderValueIsNotTakenAndTheSenderGetsAnAnswer() {
        val result = CwfSettingsMerge.merge(mapOf("a" to v("true", 30)), mapOf("a" to v("false", 20)), any)
        assertThat(result.taken).isEmpty()
        assertThat(result.merged).containsExactly("a", v("true", 30))
        assertThat(result.answer).isTrue()
    }

    @Test
    fun theSameSettingsGiveNoAnswerSoNothingEchoes() {
        val same = mapOf("a" to v("true", 30), "b" to v("3000", 5))
        val result = CwfSettingsMerge.merge(same, same, any)
        assertThat(result.taken).isEmpty()
        assertThat(result.answer).isFalse()
    }

    @Test
    fun aSettingTheSenderDoesNotHaveGivesAnAnswer() {
        val result = CwfSettingsMerge.merge(mapOf("a" to v("true", 0)), emptyMap(), any)
        assertThat(result.answer).isTrue()
        // And the other way: an unknown setting is simply taken
        assertThat(CwfSettingsMerge.merge(emptyMap(), mapOf("a" to v("true", 0)), any).taken).containsKey("a")
    }

    @Test
    fun aValueThatIsNotAllowedIsNeverTakenAndNeverStartsAnAnswer() {
        val allowed = { name: String, value: String -> name == "a" && value != "broken" }
        val result = CwfSettingsMerge.merge(
            local = mapOf("a" to v("true", 10), "old" to v("x", 10)),
            remote = mapOf("a" to v("broken", 99), "unknown" to v("1", 99)),
            allowed = allowed
        )
        assertThat(result.taken).isEmpty()
        // Our "a" counts against a remote "a" the rule cannot use; "old" is not ours to send
        assertThat(result.answer).isTrue()
        assertThat(CwfSettingsMerge.merge(mapOf("old" to v("x", 10)), emptyMap(), allowed).answer).isFalse()
    }

    @Test
    fun equalTimesAreDecidedTheSameWayOnBothSides() {
        val phone = mapOf("a" to v("false", 50))
        val watch = mapOf("a" to v("true", 50))
        val onWatch = CwfSettingsMerge.merge(watch, phone, any)
        val onPhone = CwfSettingsMerge.merge(phone, watch, any)
        assertThat(onWatch.merged).isEqualTo(onPhone.merged)
    }

    @Test
    fun oneExchangeLeavesBothSidesEqualWithAtMostOneAnswer() {
        // Changed on the phone and, while not connected, on the watch: different settings
        var phone = mapOf("a" to v("false", 100), "b" to v("true", 0), "c" to v("3000", 10))
        var watch = mapOf("a" to v("true", 0), "b" to v("false", 200), "d" to v("true", 0))

        // The phone sends, the watch merges and answers
        val onWatch = CwfSettingsMerge.merge(watch, phone, any)
        watch = onWatch.merged
        assertThat(onWatch.answer).isTrue()
        // The phone merges the answer and would not answer again
        val onPhone = CwfSettingsMerge.merge(phone, watch, any)
        phone = onPhone.merged
        assertThat(onPhone.answer).isFalse()

        assertThat(phone).isEqualTo(watch)
        assertThat(phone).containsExactly("a", v("false", 100), "b", v("false", 200), "c", v("3000", 10), "d", v("true", 0))
    }

    @Test
    fun aChangeIsNeverOlderThanTheValueItReplaces() {
        assertThat(CwfSettingsMerge.changeTime(1000, v("true", 5000))).isEqualTo(5001)
        assertThat(CwfSettingsMerge.changeTime(9000, v("true", 5000))).isEqualTo(9000)
        assertThat(CwfSettingsMerge.changeTime(9000, null)).isEqualTo(9000)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theMessageGoesThroughBothWireFormats() {
        val message = CwfSettingsSync(mapOf("show_iob" to v("false", 1234L), "wake_animation_length" to v("3000", 0L)), isAnswer = true)
        assertThat(EventData.deserialize(message.serialize())).isEqualTo(message)
        assertThat(EventData.deserializeByte(message.serializeByte())).isEqualTo(message)
        // An answer flag that is not there (an older sender) means no answer
        assertThat(EventData.deserialize(CwfSettingsSync(emptyMap()).serialize())).isEqualTo(CwfSettingsSync(emptyMap(), isAnswer = false))
    }
}
