// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// ============================================================================================
package app.aaps.wear.phonepreview

import android.app.Application
import android.text.BoringLayout
import android.text.StaticLayout
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WatchLineSpacingTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun text(value: String, width: Int = 400): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 48f)
        text = value
        layoutParams = FrameLayout.LayoutParams(width, 60)
    }

    private fun layOut(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 400)
    }

    @Test
    fun aTextOfOneLineGetsNoFallbackLineSpacingAndALongerOneKeepsIt() {
        val one = text("12:34")
        // Too narrow for the whole time: the seconds break to a second line, as in a design
        val two = text("12:34:56", width = 120)
        val hidden = text("99").apply { visibility = View.GONE }
        val root = FrameLayout(context).apply { addView(one); addView(two); addView(hidden) }
        layOut(root)
        assertThat(one.layout).isInstanceOf(BoringLayout::class.java)
        assertThat(two.layout).isInstanceOf(StaticLayout::class.java)
        // On the phone both kinds start with it on
        assertThat(one.isFallbackLineSpacing).isTrue()
        assertThat(two.isFallbackLineSpacing).isTrue()

        assertThat(WatchLineSpacing.match(root)).isTrue()
        assertThat(one.isFallbackLineSpacing).isFalse()
        assertThat(two.isFallbackLineSpacing).isTrue()
        // A view that was never laid out is left alone
        assertThat(hidden.isFallbackLineSpacing).isTrue()

        // Laid out again, nothing changes any more: the frame is drawn once
        layOut(root)
        assertThat(one.layout).isInstanceOf(BoringLayout::class.java)
        assertThat(WatchLineSpacing.match(root)).isFalse()
    }

    @Test
    fun aTextThatGetsALineMoreGetsFallbackLineSpacingBack() {
        val view = text("12:34", width = 120)
        val root = FrameLayout(context).apply { addView(view) }
        layOut(root)
        assertThat(WatchLineSpacing.match(root)).isTrue()
        assertThat(view.isFallbackLineSpacing).isFalse()

        view.text = "12:34:56"
        layOut(root)
        assertThat(view.layout).isInstanceOf(StaticLayout::class.java)
        assertThat(WatchLineSpacing.match(root)).isTrue()
        assertThat(view.isFallbackLineSpacing).isTrue()
    }
}
