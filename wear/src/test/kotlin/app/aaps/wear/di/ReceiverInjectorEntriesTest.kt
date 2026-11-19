package app.aaps.wear.di

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Every broadcast receiver of the watch app that asks for injection itself (`injectMetroMembers(this)`)
 * must have an entry in `WearMemberInjectors`. A receiver has no Metro base class, so the upstream
 * guard (`WearMemberInjectorsTest`) does not see it, and a missing entry only shows on the watch, as a
 * crash when the broadcast comes.
 *
 * That happened on 2026-11-18: the entry of `WatchAppInstallStatusReceiver` was lost, so the watch
 * never asked to install an update the phone had sent.
 */
class ReceiverInjectorEntriesTest {

    private val sourceRoot = File("src/main/kotlin").absoluteFile
    private val injectorSource = File("src/main/kotlin/app/aaps/wear/di/WearMemberInjectors.kt").absoluteFile

    private val mappedClasses: Set<String> by lazy { CLASS_KEY.findAll(injectorSource.readText()).map { it.groupValues[1] }.toSet() }

    /** The receivers that inject themselves, with the file each is in. */
    private val injectedReceivers: Map<String, String> by lazy {
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, text) -> INJECTS_ITSELF in text }
            .flatMap { (file, text) -> RECEIVER.findAll(text).map { it.groupValues[1] to file.name } }
            .toMap()
    }

    @Test
    fun theGuardHasSomethingToCheck() {
        assertThat(mappedClasses).isNotEmpty()
        assertThat(injectedReceivers.keys).contains("WatchAppInstallStatusReceiver")
    }

    @Test
    fun everyReceiverThatInjectsItselfHasAnEntry() {
        assertThat(injectedReceivers.filterKeys { it !in mappedClasses }).isEmpty()
    }

    private companion object {

        const val INJECTS_ITSELF = "injectMetroMembers(this)"
        val CLASS_KEY = Regex("""@ClassKey\((\w+)::class\)""")
        val RECEIVER = Regex("""^(?:internal\s+)?class\s+(\w+)[^\n:]*:\s*BroadcastReceiver\(\)""", RegexOption.MULTILINE)
    }
}
