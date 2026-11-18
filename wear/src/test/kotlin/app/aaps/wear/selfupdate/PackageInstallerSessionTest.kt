package app.aaps.wear.selfupdate

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The install session also starts on Wear OS 3 (Android 11), which has no `setRequireUserAction` */
@RunWith(RobolectricTestRunner::class)
internal class PackageInstallerSessionTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [30])
    fun `a session starts on Android 11`() {
        val session = PackageInstallerSession(context, 1_000)

        session.output.write(ByteArray(10))
        session.abandon()

        assertThat(context.packageManager.packageInstaller.allSessions).isEmpty()
    }

    @Test
    @Config(sdk = [35])
    fun `a session starts on Android 15`() {
        val session = PackageInstallerSession(context, 1_000)

        session.output.write(ByteArray(10))
        session.abandon()

        assertThat(context.packageManager.packageInstaller.allSessions).isEmpty()
    }
}
