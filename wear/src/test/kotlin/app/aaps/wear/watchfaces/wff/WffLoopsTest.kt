// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import app.aaps.wear.watchfaces.wff.WffLoops.Group
import app.aaps.wear.watchfaces.wff.WffLoops.Span
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class WffLoopsTest {

    private val loops = WffLoops(wakeMs = 15_000, fadeMs = 600)

    private fun layer(index: Int, slot: String, blends: Boolean = false) =
        WffPlanItem("L$index", WffTier.BAKE, "", layer = index, loop = true, slot = slot, blends = blends)

    @Test
    fun aPictureInMotionIsDrawnFromStartToEndOfTheWake() {
        val times = loops.runTake()!!.atMs
        assertThat(times.first()).isEqualTo(0L)
        assertThat(times.last()).isEqualTo(15_000L)
        assertThat(times).hasSize(15 * WffLoops.RUN_FPS + 1)
    }

    @Test
    fun aPictureInMotionStaysInsideItsShareOfTheBudget() {
        // A long wake: 4 a second would be 121 frames, more than 40 % of 200
        val take = WffLoops(wakeMs = 30_000, fadeMs = 600).runTake()!!
        assertThat(take.atMs.size).isAtMost((WffLoops.MAX_FRAMES * WffLoops.RUN_SHARE).toInt())
        assertThat(take.atMs.first()).isEqualTo(0L)
        assertThat(take.atMs.last()).isEqualTo(30_000L)
        // No budget left: no frames
        val spent = WffLoops(wakeMs = 15_000, fadeMs = 600).apply { spend(WffLoops.MAX_FRAMES) }
        assertThat(spent.runTake()).isNull()
    }

    @Test
    fun aSpanHasOneStepMoreOnEachSideButStaysInTheWake() {
        assertThat(loops.spanTimes()).hasSize(31)
        assertThat(loops.span(emptyList())).isNull()
        assertThat(loops.span(listOf(0L, 500L, 1000L))).isEqualTo(Span(0, 1500))
        assertThat(loops.span(listOf(5000L, 15_000L))).isEqualTo(Span(4500, 15_000))
    }

    @Test
    fun layersOfTheWholeWakeShareALoopPerSlotAndBlend() {
        val plan = WffPlan("t", 4, listOf(layer(2, "back"), layer(10, "back"), layer(3, "front"), layer(4, "back"), layer(5, "back")), emptyList(), 1)
        val shown = mapOf(10 to Span(0, 15_000), 2 to Span(500, 15_000), 3 to Span(0, 13_000), 4 to Span(0, 15_000), 5 to Span(6000, 9000))
        val groups = loops.groups(shown, plan, mapOf(4 to "PLUS"))
        // The whole wake first, by their lowest layer (as a number: 2 before 10); then the parts of it
        assertThat(groups).containsExactly(
            Group(setOf(10, 2), null), Group(setOf(3), null), Group(setOf(4), null), Group(setOf(5), Span(6000, 9000))
        ).inOrder()
    }

    @Test
    fun aLoopOfTheWholeWakeIsTakenAfterTheFadeIn() {
        val take = loops.take(null)!!
        assertThat(take.fps).isEqualTo(WffLoops.LOOP_FPS)
        assertThat(take.atMs).hasSize(30)
        assertThat(take.atMs.first()).isEqualTo(600L)
        assertThat(take.delaySeconds).isEqualTo(0f)
        // 2 s of frames repeat over a wake of 15 s
        assertThat(loops.loopCount(take, 30)).isEqualTo(8)
        // A short wake: the fade in is at most a quarter of it
        assertThat(WffLoops(wakeMs = 2000, fadeMs = 1000).take(null)!!.atMs.first()).isEqualTo(500L)
    }

    @Test
    fun aPartOfTheWakePlaysOnceAtMostAtThePartRate() {
        val take = loops.take(Span(6000, 9000))!!
        assertThat(take.fps).isEqualTo(WffLoops.PART_FPS)
        assertThat(take.atMs).hasSize(24)
        assertThat(take.atMs.first()).isEqualTo(6000L)
        assertThat(take.delaySeconds).isEqualTo(6f)
        assertThat(loops.loopCount(take, 24)).isEqualTo(1)
    }

    @Test
    fun theBudgetIsSharedByAllLoopsOfAFace() {
        assertThat(loops.budget).isEqualTo(WffLoops.MAX_FRAMES)
        loops.spend(WffLoops.MAX_FRAMES - 10)
        // A part of the wake gets fewer frames a second
        val take = loops.take(Span(0, 5000))!!
        assertThat(take.fps).isEqualTo(2)
        assertThat(take.atMs).hasSize(10)
        loops.spend(9)
        assertThat(loops.take(null)).isNull()
    }

    @Test
    fun aSeeThroughPictureWithBlendingLayersDoesNotMove() {
        assertThat(WffLoops.runCanMove(0)).isTrue()
        // A see-through picture cannot hide its rest pose under its frames
        assertThat(WffLoops.runCanMove(1)).isFalse()
    }
}
