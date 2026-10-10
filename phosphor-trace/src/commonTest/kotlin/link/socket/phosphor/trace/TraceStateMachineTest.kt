package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import link.socket.phosphor.lumos.VoxelFrame

/**
 * How a state change moves the playhead.
 *
 * Every trace here runs at 10 fps, so one recorded frame spans 100 ms. The
 * three-state fixture lays out `idle` over frames 0..5, `thinking` over 6..11,
 * `uncertain` over 12..17, `idle→thinking` over 18..21, and `thinking→idle`
 * over 22..25 — so an assertion on a frame index says which segment is playing
 * and how far into it the playhead has travelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TraceStateMachineTest {
    // ---------------------------------------------------------------------
    // Resolution order: a recorded transition wins
    // ---------------------------------------------------------------------

    @Test
    fun aRecordedTransitionStartsAtOnceWithoutWaitingForTheLoopSeam() {
        val machine = machineOf()
        machine.advance(250.milliseconds)
        assertEquals(2, machine.currentFrameIndex)

        machine.requestState("thinking").getOrThrow()

        assertEquals("idle→thinking", machine.activeSegment.name)
        assertEquals(18, machine.currentFrameIndex)
        // Arriving, not arrived.
        assertEquals("idle", machine.state.value)
        assertEquals("thinking", machine.requestedState.value)
    }

    @Test
    fun aRecordedTransitionPlaysOnceAndThenEntersTheTargetLoop() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()

        val walked =
            List(6) {
                machine.advance(100.milliseconds)
                machine.currentFrameIndex
            }

        // Four transition frames, then the target's loop from its first frame.
        assertEquals(listOf(19, 20, 21, 6, 7, 8), walked)
        assertEquals("thinking", machine.state.value)
        assertFalse(machine.isTransitioning)
    }

    @Test
    fun aRecordedTransitionBeatsTheConfiguredPolicy() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 500))

        machine.requestState("thinking").getOrThrow()

        assertNull(machine.blend)
        assertEquals("idle→thinking", machine.activeSegment.name)
    }

    @Test
    fun theTransitionNameIsTheArrowConvention() {
        assertEquals("idle→thinking", TraceStateMachine.transitionName("idle", "thinking"))
    }

    // ---------------------------------------------------------------------
    // Fallback: finish the loop, then switch
    // ---------------------------------------------------------------------

    @Test
    fun anUnrecordedPairKeepsLoopingUntilTheSeam() {
        val machine = machineOf()
        machine.advance(250.milliseconds)

        machine.requestState("uncertain").getOrThrow()

        assertTrue(machine.isTransitioning)
        // Nothing has moved: the outgoing loop carries on from where it was.
        assertEquals("idle", machine.activeSegment.name)
        assertEquals(2, machine.currentFrameIndex)
    }

    @Test
    fun theSwitchLandsExactlyOnTheLoopSeam() {
        val machine = machineOf()
        machine.advance(250.milliseconds)
        machine.requestState("uncertain").getOrThrow()

        val walked =
            List(6) {
                machine.advance(100.milliseconds)
                machine.currentFrameIndex
            }

        // idle's last frame is 5; the next frame is uncertain's first, 12.
        assertEquals(listOf(3, 4, 5, 12, 13, 14), walked)
        assertEquals("uncertain", machine.state.value)
        assertFalse(machine.isTransitioning)
    }

    @Test
    fun aSwitchRequestedAtTheStartOfAnIterationWaitsOutTheWholeIteration() {
        val machine = machineOf()

        machine.requestState("uncertain").getOrThrow()
        val walked =
            List(7) {
                machine.advance(100.milliseconds)
                machine.currentFrameIndex
            }

        // Six idle frames, then the seam. "Finish the current iteration" at its
        // first frame means the whole iteration.
        assertEquals(listOf(1, 2, 3, 4, 5, 12, 13), walked)
    }

    // ---------------------------------------------------------------------
    // Fallback: crossfade
    // ---------------------------------------------------------------------

    @Test
    fun anUnrecordedPairRunsTwoHeadsAtOnce() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 500))

        machine.requestState("uncertain").getOrThrow()

        val blend = assertNotNull(machine.blend)
        assertEquals(0f, blend.weight)
        assertEquals("idle", machine.activeSegment.name)
        assertEquals("uncertain", machine.incomingSegment?.name)
        assertEquals(0, machine.currentFrameIndex)
        assertEquals(12, machine.incomingFrameIndex)
    }

    @Test
    fun theCrossfadeWeightIsLinearInTime() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 1_000))
        machine.requestState("uncertain").getOrThrow()

        // Ten recorded frames of fade. Linear and nothing but — the easing that
        // belongs to a transition was baked in at capture time.
        val weights =
            List(9) {
                machine.advance(100.milliseconds)
                machine.blend?.weight
            }

        weights.forEachIndexed { step, weight ->
            assertNear((step + 1) / 10f, assertNotNull(weight))
        }
    }

    @Test
    fun theWeightStepsPerRecordedFrameRatherThanPerTick() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 500))
        machine.requestState("uncertain").getOrThrow()

        machine.advance(25.milliseconds)
        assertEquals(0f, machine.blend?.weight)
        machine.advance(25.milliseconds)
        assertEquals(0f, machine.blend?.weight)

        machine.advance(50.milliseconds)

        // One recorded frame in, one step of weight. A 120 Hz consumer and a
        // 30 fps one therefore see the same fade, which is what makes a
        // playback log comparable across runs.
        assertEquals(0.2f, machine.blend?.weight)
    }

    @Test
    fun theBlendedFrameRidesBetweenTheTwoHeads() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 500))
        machine.requestState("uncertain").getOrThrow()

        // idle holds a signature of 0, uncertain of 2, so the blend reads the weight.
        val signatures = List(4) { StateMachineTraces.signatureOf(machine.advance(100.milliseconds)) }

        listOf(0.4f, 0.8f, 1.2f, 1.6f).forEachIndexed { index, expected ->
            assertNear(expected, signatures[index])
        }
    }

    @Test
    fun theCrossfadePromotesTheIncomingHeadOnArrival() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 500))
        machine.requestState("uncertain").getOrThrow()

        machine.advance(500.milliseconds)

        assertNull(machine.blend)
        assertNull(machine.incomingSegment)
        assertEquals("uncertain", machine.state.value)
        assertEquals("uncertain", machine.activeSegment.name)
        // The incoming head kept its own playhead through the fade: five frames in.
        assertEquals(17, machine.currentFrameIndex)
    }

    @Test
    fun aZeroLengthCrossfadeIsRejected() {
        assertFailsWith<IllegalArgumentException> { TransitionPolicy.Crossfade(durationMs = 0) }
    }

    // ---------------------------------------------------------------------
    // Re-entrancy
    // ---------------------------------------------------------------------

    @Test
    fun requestingTheCurrentStateIsANoOp() {
        val machine = machineOf()
        machine.advance(250.milliseconds)

        assertTrue(machine.requestState("idle").isSuccess)

        assertFalse(machine.isTransitioning)
        assertEquals(2, machine.currentFrameIndex)
    }

    @Test
    fun requestingTheStateAlreadyBeingHeadedTowardIsANoOp() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()
        machine.advance(100.milliseconds)
        assertEquals(19, machine.currentFrameIndex)

        machine.requestState("thinking").getOrThrow()

        // Collapsed, not restarted.
        assertEquals(19, machine.currentFrameIndex)
    }

    @Test
    fun requestingTheCurrentStateCancelsAPendingLoopBoundarySwitch() {
        val machine = machineOf()
        machine.advance(250.milliseconds)
        machine.requestState("uncertain").getOrThrow()
        assertTrue(machine.isTransitioning)

        machine.requestState("idle").getOrThrow()

        assertFalse(machine.isTransitioning)
        assertEquals("idle", machine.requestedState.value)
        // Nothing had started, so nothing needs rewinding.
        assertEquals(2, machine.currentFrameIndex)
    }

    @Test
    fun aRequestArrivingMidTransitionIsHeldUntilTheTransitionLands() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()
        machine.advance(100.milliseconds)

        machine.requestState("idle").getOrThrow()

        // Cutting a recorded one-shot in half is the pop this type prevents.
        assertEquals("idle→thinking", machine.activeSegment.name)
        assertEquals(19, machine.currentFrameIndex)
        assertEquals("idle", machine.state.value)
        assertEquals("idle", machine.requestedState.value)
    }

    @Test
    fun aRapidThereAndBackPlaysBothRecordedTransitions() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()
        machine.advance(100.milliseconds)
        machine.requestState("idle").getOrThrow()

        // The outbound one-shot finishes and the return one starts at its seam.
        machine.advance(300.milliseconds)
        assertEquals("thinking→idle", machine.activeSegment.name)
        assertEquals(22, machine.currentFrameIndex)
        assertEquals("thinking", machine.state.value)

        machine.advance(400.milliseconds)
        assertEquals("idle", machine.state.value)
        assertEquals(0, machine.currentFrameIndex)
        assertFalse(machine.isTransitioning)
    }

    @Test
    fun onlyTheLastRequestInAFlurrySurvives() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()
        machine.requestState("uncertain").getOrThrow()
        machine.requestState("idle").getOrThrow()
        machine.requestState("uncertain").getOrThrow()

        machine.advance(10_000.milliseconds)

        assertEquals("uncertain", machine.state.value)
        assertFalse(machine.isTransitioning)
    }

    // ---------------------------------------------------------------------
    // Unknown names
    // ---------------------------------------------------------------------

    @Test
    fun anUnknownStateRequestFailsWithoutDisturbingPlayback() {
        val machine = machineOf()
        machine.advance(250.milliseconds)

        val result = machine.requestState("ready")

        val error = assertIs<TracePlaybackError.UnknownSegment>(result.exceptionOrNull())
        assertEquals("ready", error.name)
        assertEquals(machine.stateNames, error.available)
        assertFalse(machine.isTransitioning)
        assertEquals("idle", machine.requestedState.value)

        machine.advance(100.milliseconds)
        assertEquals(3, machine.currentFrameIndex)
    }

    @Test
    fun anUnknownInitialStateIsRejectedAtConstruction() {
        assertFailsWith<TracePlaybackError.UnknownSegment> {
            TraceStateMachine(StateMachineTraces.threeState(), "ready")
        }
    }

    @Test
    fun theStateNamesAreTheTraceSegmentsInDeclarationOrder() {
        assertEquals(
            listOf("idle", "thinking", "uncertain", "idle→thinking", "thinking→idle"),
            machineOf().stateNames,
        )
    }

    // ---------------------------------------------------------------------
    // The clock
    // ---------------------------------------------------------------------

    @Test
    fun oneStepOverABoundaryCarriesItsRemainder() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()

        // 450 ms steps over the 400 ms transition and 50 ms into the target loop.
        machine.advance(450.milliseconds)

        assertEquals("thinking", machine.activeSegment.name)
        assertEquals(6, machine.currentFrameIndex)
        assertEquals("thinking", machine.state.value)
    }

    @Test
    fun oneStepCanCrossSeveralBoundaries() {
        val machine = machineOf()
        machine.requestState("thinking").getOrThrow()
        machine.requestState("idle").getOrThrow()

        // Through idle→thinking, through thinking→idle, and 100 ms into idle.
        machine.advance(900.milliseconds)

        assertEquals("idle", machine.state.value)
        assertEquals(1, machine.currentFrameIndex)
    }

    @Test
    fun timeRunningBackwardsIsRejected() {
        assertFailsWith<IllegalArgumentException> { machineOf().advance((-1).milliseconds) }
    }

    @Test
    fun aHeldFrameIsTheIdenticalInstance() {
        val machine = machineOf()

        val first = machine.advance(Duration.ZERO)
        val held = machine.advance(25.milliseconds)

        assertSame(first, held)
    }

    @Test
    fun aHeldBlendIsTheIdenticalInstance() {
        val machine = machineOf(TransitionPolicy.Crossfade(durationMs = 500))
        machine.requestState("uncertain").getOrThrow()

        val first = machine.advance(Duration.ZERO)
        val held = machine.advance(25.milliseconds)

        // The weight is quantized, so a refresh inside one recorded frame
        // resolves to the same blend and allocates nothing.
        assertSame(first, held)
    }

    // ---------------------------------------------------------------------
    // The frame stream
    // ---------------------------------------------------------------------

    @Test
    fun framesEmitsEachRecordedFrameOnceInOrder() =
        runTest {
            val machine = machineOf()

            val emitted = machine.frames(testTimeSource).take(5).toList()

            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), emitted.map(VoxelFrame::tick))
        }

    @Test
    fun tickingFasterThanTheRecordedFpsDoesNotDuplicateFrames() =
        runTest {
            val machine = machineOf()

            val emitted =
                machine
                    .frames(testTimeSource, tickInterval = 25.milliseconds)
                    .take(4)
                    .toList()

            assertEquals(listOf(0L, 1L, 2L, 3L), emitted.map(VoxelFrame::tick))
        }

    @Test
    fun framesRejectsANonPositiveTickInterval() {
        assertFailsWith<IllegalArgumentException> { machineOf().frames(tickInterval = 0.milliseconds) }
    }

    private companion object {
        const val TOLERANCE: Float = 1e-4f

        fun machineOf(policy: TransitionPolicy = TransitionPolicy.FinishLoopThenSwitch): TraceStateMachine =
            TraceStateMachine(StateMachineTraces.threeState(), "idle", policy)

        fun assertNear(
            expected: Float,
            actual: Float,
        ) {
            assertTrue(abs(expected - actual) <= TOLERANCE, "expected $expected but was $actual")
        }
    }
}
