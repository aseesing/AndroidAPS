// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.wear.compose

import android.net.Uri
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.plugins.sync.wear.keys.WearDesignImportKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The file picker of the design import opens where the last pick was. */
internal class CwfDesignImportPlaceTest {

    private val preferences: Preferences = mock()
    private val sut = CwfDesignImportPlace(preferences)

    private fun uri(link: String): Uri = mock<Uri>().also { whenever(it.toString()).thenReturn(link) }

    @Test
    fun `the last place is null until something was picked`() {
        whenever(preferences.get(WearDesignImportKey.LastPickedDesign)).thenReturn("")
        assertThat(sut.last()).isNull()

        whenever(preferences.get(WearDesignImportKey.LastPickedDesign)).thenReturn("content://drive/doc1")
        assertThat(sut.last()).isEqualTo("content://drive/doc1")
    }

    @Test
    fun `a pick remembers its first file`() {
        sut.remember(listOf(uri("content://drive/doc1"), uri("content://drive/doc2")))

        verify(preferences).put(WearDesignImportKey.LastPickedDesign, "content://drive/doc1")
    }

    @Test
    fun `an empty pick keeps the place of the last one`() {
        sut.remember(emptyList())

        verify(preferences, never()).put(any<StringNonPreferenceKey>(), any<String>())
    }
}
