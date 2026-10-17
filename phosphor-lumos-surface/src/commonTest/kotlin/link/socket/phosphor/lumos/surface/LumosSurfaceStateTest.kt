package link.socket.phosphor.lumos.surface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.OrbViewport
import link.socket.phosphor.lumos.RectPx
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-75: the state behind a surface, without Compose — phases, the wait for bounds, measured
 * arrival and the settled stop, follow, cancel, release, reduced motion, and the cut back to the
 * orb's form on Solid. The frame loop is driven by hand at 30 fps.
 */
class LumosSurfaceStateTest {
    @Test
    fun `a target is invisible until the cells have become it`() {
        val state = surface()
        assertEquals(SurfacePhase.Orb, state.phase)
        assertEquals(0f, state.alphaFor(TARGET))
    }

    @Test
    fun `reduced motion shows the control at once and moves no cell`() {
        val state = surface(reducedMotion = true)
        val home = state.source.builder.form

        state.assembleInto(TARGET)

        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertEquals(1f, state.alphaFor(TARGET))
        assertFalse(state.source.builder.isMorphing)
        assertSame(home, state.source.builder.form)
        assertNull(state.canvasFrame)
    }

    @Test
    fun `the config's reduced motion counts too`() {
        val state = surface(config = LumosConfig(reducedMotion = true))
        state.assembleInto(TARGET)
        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
    }

    @Test
    fun `the flight waits for the target's bounds`() {
        val state = surface()
        state.assembleInto(TARGET)

        state.run(frames = 5)

        assertEquals(SurfacePhase.Assembling(TARGET), state.phase)
        assertFalse(state.source.builder.isMorphing)
        assertNull(state.canvasFrame)
        assertEquals(0f, state.alphaFor(TARGET))
    }

    @Test
    fun `the flight waits for the viewport`() {
        val state = surface(slot = false)
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)

        state.run(frames = 5)

        assertFalse(state.source.builder.isMorphing)
        assertNull(state.viewport)
    }

    @Test
    fun `the cells fly then the control is solid and the orb's form is back`() {
        val state = surface()
        val builder = state.source.builder
        val home = builder.form
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)

        state.run(frames = 1)

        assertTrue(builder.isMorphing, "the first frame with bounds starts the morph")
        assertEquals(SurfacePhase.Assembling(TARGET), state.phase)
        assertNotNull(state.canvasFrame, "the overlay has a frame to draw")
        assertTrue(state.arrival < 1f)
        val form = assertNotNull(state.form)
        assertEquals(BUTTON.left, form.rect.left)
        assertEquals(CORNER, form.rect.cornerRadius)

        state.run(frames = 4 * FPS)

        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertEquals(1f, state.alphaFor(TARGET))
        assertEquals(1f, state.arrival)
        assertNull(state.canvasFrame, "nothing is drawn once solid")
        assertFalse(builder.isMorphing)
        assertSame(home, builder.form, "the builder is cut back to the orb's form")
        assertNull(state.form)
    }

    @Test
    fun `the hand-off is gradual — arrival passes through the middle before the control is solid`() {
        val state = surface()
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)
        val seen = mutableListOf<Float>()

        while (state.phase is SurfacePhase.Assembling && seen.size < 4 * FPS) {
            state.run(frames = 1)
            seen += state.arrival
        }

        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertEquals(0f, seen.first(), "the cells start out on the orb")
        assertTrue(seen.any { it in 0.2f..0.9f }, "arrival jumped rather than rose: $seen")
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a - 0.25f }, "arrival fell back hard: $seen")
    }

    @Test
    fun `the slot keeps its orb until the overlay has a frame to draw`() {
        val state = surface()
        assertTrue(state.orbInSlot)
        state.assembleInto(TARGET)

        state.run(frames = 3)
        assertTrue(state.orbInSlot, "the flight waits for the target's bounds and the orb stays")

        state.targetPositioned(TARGET, BUTTON, CORNER)
        state.run(frames = 1)
        assertNotNull(state.canvasFrame)
        assertFalse(state.orbInSlot, "the overlay has the cells")

        state.run(frames = 4 * FPS)
        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertFalse(state.orbInSlot)
        state.release()
        assertTrue(state.orbInSlot)
    }

    @Test
    fun `the surface draws Dots by default`() {
        assertEquals(SurfaceStyle.Dots, LumosSurfaceState(LiveFrameSource(AtmospherePresets.IDLE)).config.style)
    }

    @Test
    fun `every cell flies by default`() {
        val state = surface()
        val count = state.source.builder.currentCount
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)

        state.run(frames = 1)

        assertTrue(state.source.builder.isMorphing)
        assertEquals(count, state.source.builder.currentCount)
        assertNull(state.surplus)
    }

    @Test
    fun `a faded surplus flies and fades under the burst then the morph keeps the rest`() {
        val state = surface()
        val builder = state.source.builder
        val home = builder.form
        val count = builder.currentCount
        val fade = SurfaceSurplus.Fade(spacingPx = 8f)
        val keep = fade.keep(BUTTON_RECT, count)
        assertTrue(keep < count, "the test needs a surplus: keeps $keep of $count")
        state.assembleInto(TARGET, surplus = fade)
        state.targetPositioned(TARGET, BUTTON, CORNER)

        state.run(frames = 1)

        val mask = assertNotNull(state.surplus, "the fade starts with the flight")
        assertEquals(count - keep, mask.count { it })
        assertEquals(count, builder.currentCount, "nothing is dropped while it fades")
        assertTrue(builder.isMorphing, "the burst is in flight")
        assertSame(home, builder.form, "the morph waits for the fade")
        // Survivors are spread through the orb, not one side of it: both halves of the index range keep cells.
        assertTrue(mask.take(count / 2).any { !it } && mask.drop(count / 2).any { !it })
        val drawn = assertNotNull(state.canvasFrame)
        assertTrue(drawn.voxels.isNotEmpty())

        state.run(frames = 3)
        assertTrue(state.surplusFade > 0f && state.surplusFade < 1f, "fading, at ${state.surplusFade}")

        state.run(frames = 4)
        assertNull(state.surplus, "faded out after 200 ms")
        assertEquals(keep, builder.currentCount, "the morph dropped the surplus")
        assertNotNull(state.form)

        state.run(frames = 4 * FPS)
        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertSame(home, builder.form, "the builder is cut back to the orb's form")
        assertEquals(count, builder.currentCount)
    }

    @Test
    fun `a faded surplus dims the frame the overlay draws while it fades`() {
        val state = surface()
        state.assembleInto(TARGET, surplus = SurfaceSurplus.Fade(spacingPx = 8f))
        state.targetPositioned(TARGET, BUTTON, CORNER)
        state.run(frames = 1)
        val unfaded = assertNotNull(state.canvasFrame).voxels.sumOf { it.alpha.toDouble() }

        state.run(frames = 3)

        // About five in eight cells are leaving, at half alpha by now: the frame's ink falls by about a third.
        assertTrue(state.surplusFade in 0.4f..0.6f, "fading, at ${state.surplusFade}")
        val fading = assertNotNull(state.canvasFrame).voxels.sumOf { it.alpha.toDouble() }
        assertTrue(fading < 0.8 * unfaded, "the frame did not dim: $fading of $unfaded")
    }

    @Test
    fun `a control that needs every cell keeps them all under Fade`() {
        val state = surface()
        val count = state.source.builder.currentCount
        state.assembleInto(TARGET, surplus = SurfaceSurplus.Fade())
        state.targetPositioned(TARGET, BUTTON, CORNER)

        state.run(frames = 1)

        assertNull(state.surplus)
        assertTrue(state.source.builder.isMorphing)
        assertEquals(count, state.source.builder.currentCount)
    }

    @Test
    fun `Fade keeps the fill its spacing needs — at most the count and at least one`() {
        val fade = SurfaceSurplus.Fade()
        // PHO-68's button: 1,168 cells at 2.3 px, Dots' 18 % on the outline.
        assertEquals(1168, fade.keep(BUTTON_RECT, 4801))
        assertEquals(500, fade.keep(BUTTON_RECT, 500))
        assertEquals(1, SurfaceSurplus.Fade(spacingPx = 1000f).keep(BUTTON_RECT, 4801))
        assertEquals(listOf(0, 3, 6), evenSurvivors(9, 3).toList())
        assertEquals(listOf(0, 2, 5, 7), evenSurvivors(10, 4).toList())
    }

    @Test
    fun `a moving target is followed`() {
        val state = surface()
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)
        state.run(frames = 3)

        val moved = BUTTON.translate(Offset(-40f, -20f))
        state.targetPositioned(TARGET, moved, CORNER)
        state.run(frames = 1)

        val form = assertNotNull(state.form)
        assertEquals(moved.left, form.rect.left)
        assertEquals(moved.top, form.rect.top)
        assertTrue(state.source.builder.isMorphing, "a move does not cut the morph")
    }

    @Test
    fun `cancel snaps the control on and stops`() {
        val state = surface()
        val home = state.source.builder.form
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)
        state.run(frames = 3)

        state.cancel()

        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertEquals(1f, state.alphaFor(TARGET))
        assertNull(state.canvasFrame)
        assertFalse(state.source.builder.isMorphing)
        assertSame(home, state.source.builder.form)
    }

    @Test
    fun `cancel before the flight starts still finishes`() {
        val state = surface()
        state.assembleInto(TARGET)

        state.cancel()

        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
        assertEquals(1f, state.alphaFor(TARGET))
    }

    @Test
    fun `a resize mid-flight cancels`() {
        val state = surface()
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON, CORNER)
        state.run(frames = 3)

        state.surfaceSized(300, 200)

        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
    }

    @Test
    fun `release gives the surface back to the orb`() {
        val state = surface(reducedMotion = true)
        state.assembleInto(TARGET)

        state.release()

        assertEquals(SurfacePhase.Orb, state.phase)
        assertEquals(0f, state.alphaFor(TARGET))
        assertEquals(0f, state.arrival)
    }

    @Test
    fun `assembleInto is ignored unless the surface is an orb`() {
        val state = surface()
        state.assembleInto(TARGET)
        state.assembleInto("other")
        assertEquals(SurfacePhase.Assembling(TARGET), state.phase)
        assertEquals(0f, state.alphaFor("other"))

        state.cancel()
        state.assembleInto("other")
        assertEquals(SurfacePhase.Solid(TARGET), state.phase)
    }

    @Test
    fun `cancel and release do nothing out of their phase`() {
        val state = surface()
        state.cancel()
        state.release()
        assertEquals(SurfacePhase.Orb, state.phase)
    }

    @Test
    fun `a surface not at the root measures targets and the slot in its own pixels`() {
        val state = surface()
        state.surfacePositioned(Offset(10f, 20f))
        state.assembleInto(TARGET)
        state.targetPositioned(TARGET, BUTTON.translate(Offset(10f, 20f)), CORNER)

        state.run(frames = 1)

        val form = assertNotNull(state.form)
        assertEquals(BUTTON.left, form.rect.left)
        assertEquals(BUTTON.top, form.rect.top)
        assertEquals(SLOT.center.x - 10f, form.viewport.centerXPx)
        assertEquals(SLOT.center.y - 20f, form.viewport.centerYPx)
        assertEquals(64f, form.viewport.radiusPx)
    }

    @Test
    fun `an explicit viewport overrides the slot`() {
        val state = surface()
        val given = OrbViewport(50f, 50f, 20f)
        state.viewport = given
        assertEquals(given, state.viewport)
    }

    private fun surface(
        reducedMotion: Boolean = false,
        config: LumosConfig = LumosConfig(),
        slot: Boolean = true,
    ): LumosSurfaceState {
        val state =
            LumosSurfaceState(
                source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = RESOLUTION)),
                config = config,
            )
        state.platformReducesMotion = { reducedMotion }
        state.attach(LookClock())
        state.surfaceSized(WIDTH, HEIGHT)
        if (slot) state.slotPositioned(SLOT)
        return state
    }

    private var now = 0L

    private fun LumosSurfaceState.run(frames: Int) {
        repeat(frames) {
            now += FRAME_NANOS
            step(now)
        }
    }

    private companion object {
        const val TARGET = "turn-on"
        const val RESOLUTION = 4
        const val WIDTH = 400
        const val HEIGHT = 260
        const val FPS = 30
        const val FRAME_NANOS = 33_333_333L
        const val CORNER = 8f

        // The orb's slot: centre (200, 100), radius 64. The button: 128 × 40 at the bottom right.
        val SLOT = Rect(136f, 36f, 264f, 164f)
        val BUTTON = Rect(248f, 196f, 376f, 236f)
        val BUTTON_RECT = RectPx(248f, 196f, 128f, 40f, CORNER)
    }
}
