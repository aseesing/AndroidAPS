package app.aaps.wear.watchfaces.utils

import android.content.Context
import android.content.ContextWrapper
import app.aaps.wear.watchfaces.CustomWatchface
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The system makes a second, headless copy of the watch face and calls it on a background thread
 * (`createComplicationSlotsManager`) while the main thread may call it too (`onDestroy`). Both start
 * with `ensureInjected`, so that must be safe on two threads: one injection, and no thread that goes
 * on before the fields are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BaseWatchFaceInjectionTest {

    private val ensureInjected = BaseWatchFace::class.java.getDeclaredMethod("ensureInjected").apply { isAccessible = true }
    private val injectionComplete = BaseWatchFace::class.java.getDeclaredField("injectionComplete").apply { isAccessible = true }

    @Test
    fun theInjectionIsGuardedForTwoThreads() {
        assertThat(Modifier.isSynchronized(ensureInjected.modifiers)).isTrue()
        assertThat(Modifier.isVolatile(injectionComplete.modifiers)).isTrue()
    }

    @Test
    fun manyThreadsAtOnceAllSeeAnInjectedFace() {
        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
        repeat(20) { round ->
            // A headless copy: a context, but no onCreate and so no injection yet
            val face = CustomWatchface()
            attach.invoke(face, RuntimeEnvironment.getApplication())
            val go = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val threads = (1..4).map {
                thread {
                    try {
                        go.await()
                        ensureInjected.invoke(face)
                        // What the caller does next: it uses an injected field
                        checkNotNull(face.complicationDataRepository)
                        checkNotNull(face.aapsLogger)
                        checkNotNull(face.sp)
                    } catch (e: Throwable) {
                        synchronized(failures) { failures += e }
                    }
                }
            }
            go.countDown()
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
            assertWithMessage("round $round").that(failures).isEmpty()
            assertThat(injectionComplete.getBoolean(face)).isTrue()
        }
    }
}
