package app.aaps.plugins.sync.wear

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A [Preferences] mock that keeps what is put in it, for the non-preference keys of the night face
 * tests. [writes] counts the puts, so a test can check that nothing was written.
 */
class PreferencesInMemory {

    val values = mutableMapOf<Any, Any>()
    var writes = 0
        private set

    val preferences: Preferences = mock()

    init {
        whenever(preferences.get(any<StringNonPreferenceKey>())).doAnswer { values[it.arguments[0]] as String? ?: (it.arguments[0] as StringNonPreferenceKey).defaultValue }
        whenever(preferences.get(any<BooleanNonPreferenceKey>())).doAnswer { values[it.arguments[0]] as Boolean? ?: (it.arguments[0] as BooleanNonPreferenceKey).defaultValue }
        whenever(preferences.get(any<IntNonPreferenceKey>())).doAnswer { values[it.arguments[0]] as Int? ?: (it.arguments[0] as IntNonPreferenceKey).defaultValue }
        whenever(preferences.get(any<BooleanPreferenceKey>())).doAnswer { values[it.arguments[0]] as Boolean? ?: (it.arguments[0] as BooleanPreferenceKey).defaultValue }
        doAnswer { store(it.arguments[0], it.arguments[1]) }.whenever(preferences).put(any<StringNonPreferenceKey>(), any<String>())
        doAnswer { store(it.arguments[0], it.arguments[1]) }.whenever(preferences).put(any<BooleanNonPreferenceKey>(), any<Boolean>())
        doAnswer { store(it.arguments[0], it.arguments[1]) }.whenever(preferences).put(any<IntNonPreferenceKey>(), any<Int>())
    }

    private fun store(key: Any, value: Any) {
        values[key] = value
        writes++
    }
}
