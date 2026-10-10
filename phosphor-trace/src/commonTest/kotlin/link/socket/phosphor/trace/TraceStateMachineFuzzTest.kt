package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seeded random request sequences against a synthetic trace, checked for the
 * four properties a pop-free state machine has to hold.
 *
 * 1. **No out-of-range frame.** Every index either head resolves to sits inside
 *    the segment that head is playing, and inside the recording.
 * 2. **No discontinuity past the envelope.** The fixture's frame signatures are
 *    laid out so a correct playback's largest step is bounded — see
 *    [StateMachineTraces]. A cut transition or an unfaded switch exceeds it.
 * 3. **The terminal state is always reached.** However the requests interleave,
 *    draining the clock settles on the last one.
 * 4. **Same seed, same playback.** The frame-by-frame log is reproducible, which
 *    is what makes a replay auditable.
 *
 * Three seeds are pinned so a regression reproduces rather than merely appearing.
 */
class TraceStateMachineFuzzTest {
    // ---------------------------------------------------------------------
    // 1. Frame indices stay inside their segment
    // ---------------------------------------------------------------------

    @Test
    fun noHeadEverLeavesItsSegment() {
        SEEDS.forEach { seed ->
            FIXTURES.forEach { fixture ->
                val playback = play(fixture, seed)
                val bounds = fixture.trace.segments.associateBy(TraceSegment::name)

                playback.log.forEachIndexed { tick, entry ->
                    val outgoing = bounds.getValue(entry.segment)
                    assertTrue(
                        entry.frameIndex in outgoing.startFrame..outgoing.endFrame,
                        "${fixture.label} seed=$seed tick=$tick: index ${entry.frameIndex} " +
                            "outside '${entry.segment}' (${outgoing.startFrame}..${outgoing.endFrame})",
                    )
                    assertTrue(
                        entry.frameIndex in 0 until fixture.trace.frameCount,
                        "${fixture.label} seed=$seed tick=$tick: index ${entry.frameIndex} outside the trace",
                    )
                    val incoming = entry.incoming?.let { bounds.getValue(it) } ?: return@forEachIndexed
                    val incomingIndex = entry.incomingIndex ?: error("a fading tick must carry an incoming index")
                    assertTrue(
                        incomingIndex in incoming.startFrame..incoming.endFrame,
                        "${fixture.label} seed=$seed tick=$tick: incoming index $incomingIndex " +
                            "outside '${entry.incoming}'",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 2. Continuity
    // ---------------------------------------------------------------------

    @Test
    fun crossfadedPlaybackStaysInsideItsEnvelope() {
        val fixture = THREE_STATE_CROSSFADE
        val envelope = StateMachineTraces.continuityEnvelope(fixture.states, StateMachineTraces.fadeSteps(FADE_MS))

        SEEDS.forEach { seed ->
            val signatures = play(fixture, seed).log.map { it.signature }

            signatures.zipWithNext().forEachIndexed { tick, (previous, next) ->
                assertTrue(
                    abs(next - previous) <= envelope + TOLERANCE,
                    "seed=$seed tick=${tick + 1}: signature jumped $previous → $next, " +
                        "past the $envelope envelope",
                )
            }
        }
    }

    @Test
    fun recordedTransitionsAlwaysPlayToTheirLastFrame() {
        val fixture = AUTHORED_FIVE_STATE
        val bounds = fixture.trace.segments.associateBy(TraceSegment::name)

        SEEDS.forEach { seed ->
            val playback = play(fixture, seed)

            // Every pair is recorded, so the fallback policies are dead weight.
            assertTrue(
                playback.log.none { it.incoming != null },
                "seed=$seed: a crossfade ran even though every pair has a recorded transition",
            )

            val runs = playback.runs()
            runs.forEachIndexed { index, played ->
                // The last run may be cut off mid-segment by the sampling window.
                if (index == runs.lastIndex) return@forEachIndexed
                val (from, to) = played.transitionEndpoints() ?: return@forEachIndexed
                val segment = bounds.getValue(played.segment)

                assertEquals(
                    (played.indices.first()..segment.endFrame).toList(),
                    played.indices,
                    "seed=$seed: '${played.segment}' did not walk to its last frame",
                )
                runs.getOrNull(index - 1)?.let { previous ->
                    assertTrue(
                        previous.segment == from || previous.endsAt(from),
                        "seed=$seed: '${played.segment}' was entered from '${previous.segment}'",
                    )
                }
                runs.getOrNull(index + 1)?.let { next ->
                    assertTrue(
                        next.segment == to || next.startsAt(to),
                        "seed=$seed: '${played.segment}' handed off to '${next.segment}'",
                    )
                }
            }
        }
    }

    @Test
    fun anUnfadedSwitchAlwaysLandsOnTheLoopSeam() {
        val fixture = THREE_STATE_FINISH_LOOP
        val bounds = fixture.trace.segments.associateBy(TraceSegment::name)
        val loops = fixture.states.toSet()

        SEEDS.forEach { seed ->
            val runs = play(fixture, seed).runs()

            runs.zipWithNext().forEach { (previous, next) ->
                if (previous.segment !in loops || next.segment !in loops) return@forEach

                // A loop handing straight over to another loop is the
                // finish-loop-then-switch path, and it may only do so at the seam.
                assertEquals(
                    bounds.getValue(previous.segment).endFrame,
                    previous.indices.last(),
                    "seed=$seed: '${previous.segment}' → '${next.segment}' switched off-seam",
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 3. The terminal state
    // ---------------------------------------------------------------------

    @Test
    fun theLastRequestIsAlwaysWhereThePlayheadSettles() {
        SEEDS.forEach { seed ->
            FIXTURES.forEach { fixture ->
                val playback = play(fixture, seed)

                assertEquals(
                    playback.terminalRequest,
                    playback.terminalState,
                    "${fixture.label} seed=$seed: settled somewhere other than the last request",
                )
                assertFalse(
                    playback.stillTransitioning,
                    "${fixture.label} seed=$seed: still transitioning after the clock was drained",
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 4. Determinism
    // ---------------------------------------------------------------------

    @Test
    fun oneSeedProducesOnePlayback() {
        SEEDS.forEach { seed ->
            FIXTURES.forEach { fixture ->
                val first = play(fixture, seed)
                val second = play(fixture, seed)

                assertEquals(first.requests, second.requests, "${fixture.label} seed=$seed: requests diverged")
                assertEquals(first.log, second.log, "${fixture.label} seed=$seed: playback diverged")
            }
        }
    }

    @Test
    fun differentSeedsProduceDifferentPlaybacks() {
        // Guards the fuzz itself: a schedule that collapsed to the same walk for
        // every seed would make the three pinned seeds one seed.
        val playbacks = SEEDS.map { play(THREE_STATE_CROSSFADE, it).log }

        assertEquals(SEEDS.size, playbacks.distinct().size)
    }

    @Test
    fun theFuzzActuallyExercisesEveryPath() {
        // Guards the fuzz itself: assertions over a walk that never transitioned
        // would pass vacuously.
        val crossfaded = play(THREE_STATE_CROSSFADE, SEEDS.first())
        val recorded = play(AUTHORED_FIVE_STATE, SEEDS.first())
        val seamed = play(THREE_STATE_FINISH_LOOP, SEEDS.first())

        assertTrue(crossfaded.log.any { it.incoming != null }, "no crossfade ran")
        assertTrue(crossfaded.log.any { it.segment.contains(ARROW) }, "no recorded transition ran")
        assertTrue(recorded.log.any { it.segment.contains(ARROW) }, "no recorded transition ran")
        assertTrue(seamed.runs().size > 1, "nothing ever switched")
        assertTrue(crossfaded.rejectedRequests > 0, "no unknown state was ever requested")
    }

    // ---------------------------------------------------------------------
    // The harness
    // ---------------------------------------------------------------------

    /**
     * One sampled frame of playback — everything an observer can see, which is
     * what makes it the right unit for a reproducible log.
     */
    private data class Tick(
        val frameIndex: Int,
        val segment: String,
        val incoming: String?,
        val incomingIndex: Int?,
        val state: String,
        val requested: String,
        val signature: Float,
    )

    /** A maximal run of consecutive ticks on one segment. */
    private data class Run(
        val segment: String,
        val indices: List<Int>,
    ) {
        /** `from` and `to` when this run is a recorded transition, else null. */
        fun transitionEndpoints(): Pair<String, String>? {
            val halves = segment.split(TraceStateMachine.TRANSITION_ARROW)
            return if (halves.size == 2) halves[0] to halves[1] else null
        }

        fun endsAt(state: String): Boolean = transitionEndpoints()?.second == state

        fun startsAt(state: String): Boolean = transitionEndpoints()?.first == state
    }

    private class Playback(
        val log: List<Tick>,
        val requests: List<Pair<Int, String>>,
        val rejectedRequests: Int,
        val terminalRequest: String,
        val terminalState: String,
        val stillTransitioning: Boolean,
    ) {
        fun runs(): List<Run> {
            val runs = ArrayList<Run>()
            var current: String? = null
            var indices = ArrayList<Int>()
            log.forEach { entry ->
                if (entry.segment != current) {
                    current?.let { runs += Run(it, indices) }
                    current = entry.segment
                    indices = ArrayList()
                }
                indices += entry.frameIndex
            }
            current?.let { runs += Run(it, indices) }
            return runs
        }
    }

    private class Fixture(
        val label: String,
        val trace: VoxelTrace,
        val states: List<String>,
        val policy: TransitionPolicy,
        /** Whether the clock steps by exactly one recorded frame or by ragged deltas. */
        val onGrid: Boolean,
    )

    private fun play(
        fixture: Fixture,
        seed: Int,
    ): Playback {
        val random = Random(seed)
        val machine = TraceStateMachine(fixture.trace, fixture.states.first(), fixture.policy)
        val frameNanos = machine.frameDuration.inWholeNanoseconds
        val log = ArrayList<Tick>(TICKS)
        val requests = ArrayList<Pair<Int, String>>()
        var rejected = 0
        var lastRequest = fixture.states.first()

        repeat(TICKS) { tick ->
            if (random.nextInt(REQUEST_ODDS) == 0) {
                val name = fixture.states.random(random)
                machine.requestState(name).getOrThrow()
                requests += tick to name
                lastRequest = name
            }
            if (random.nextInt(UNKNOWN_ODDS) == 0) {
                // An unknown name must fail without touching playback.
                assertTrue(machine.requestState(UNKNOWN_STATE).isFailure)
                rejected++
            }

            val delta =
                if (fixture.onGrid) {
                    machine.frameDuration
                } else {
                    random.nextLong(1L, frameNanos * 2L).nanoseconds
                }
            val frame = machine.advance(delta)

            log +=
                Tick(
                    frameIndex = machine.currentFrameIndex,
                    segment = machine.activeSegment.name,
                    incoming = machine.incomingSegment?.name,
                    incomingIndex = machine.incomingFrameIndex,
                    state = machine.state.value,
                    requested = machine.requestedState.value,
                    signature = StateMachineTraces.signatureOf(frame),
                )
        }

        // Drain: no pending chain is deeper than one transition, so this always
        // settles. If it did not, the terminal-state assertion would say so.
        machine.advance(DRAIN)

        return Playback(
            log = log,
            requests = requests,
            rejectedRequests = rejected,
            terminalRequest = lastRequest,
            terminalState = machine.state.value,
            stillTransitioning = machine.isTransitioning,
        )
    }

    private companion object {
        /** Pinned so a fuzz failure reproduces rather than merely recurring. */
        val SEEDS: List<Int> = listOf(20_260_610, 42, 987_654_321)

        const val TICKS: Int = 400
        const val REQUEST_ODDS: Int = 7
        const val UNKNOWN_ODDS: Int = 23
        const val FADE_MS: Long = 500L
        const val TOLERANCE: Float = 1e-4f
        const val UNKNOWN_STATE: String = "no-such-state"
        const val ARROW: String = TraceStateMachine.TRANSITION_ARROW
        val DRAIN: Duration = 60.seconds

        val THREE_STATE_CROSSFADE =
            Fixture(
                label = "three-state/crossfade",
                trace = StateMachineTraces.threeState(),
                states = StateMachineTraces.THREE_STATES,
                policy = TransitionPolicy.Crossfade(FADE_MS),
                onGrid = true,
            )

        val THREE_STATE_FINISH_LOOP =
            Fixture(
                label = "three-state/finish-loop",
                trace = StateMachineTraces.threeState(),
                states = StateMachineTraces.THREE_STATES,
                policy = TransitionPolicy.FinishLoopThenSwitch,
                onGrid = true,
            )

        val AUTHORED_FIVE_STATE =
            Fixture(
                label = "authored-five-state",
                trace = StateMachineTraces.authoredFiveState(),
                states = StateMachineTraces.FIVE_STATES,
                policy = TransitionPolicy.FinishLoopThenSwitch,
                onGrid = true,
            )

        /** Ragged deltas: the same machine driven by a clock that never lands on a frame. */
        val THREE_STATE_OFF_GRID =
            Fixture(
                label = "three-state/off-grid",
                trace = StateMachineTraces.threeState(),
                states = StateMachineTraces.THREE_STATES,
                policy = TransitionPolicy.Crossfade(FADE_MS),
                onGrid = false,
            )

        val FIXTURES =
            listOf(
                THREE_STATE_CROSSFADE,
                THREE_STATE_FINISH_LOOP,
                AUTHORED_FIVE_STATE,
                THREE_STATE_OFF_GRID,
            )
    }
}
