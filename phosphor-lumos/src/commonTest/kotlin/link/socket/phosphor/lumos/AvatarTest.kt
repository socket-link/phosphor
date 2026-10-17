package link.socket.phosphor.lumos

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import link.socket.phosphor.math.Vector2

/**
 * PHO-67 Task A: the [Avatar] keeps the catalogue contract — exactly `count` cells inside the
 * unit sphere, deterministic, held while nothing changes — and its eyes are holes: no body cell
 * sits inside either eye disc.
 */
class AvatarTest {
    @Test
    fun `the avatar returns exactly count positions inside the unit sphere`() {
        val avatar = Avatar()
        for (count in COUNTS) {
            val sample = avatar.sample(count, LumosLook.BASE, 0f)
            assertEquals(count, sample.count, "at count $count")
            sample.positions.forEachIndexed { index, position ->
                val length = position.length()
                if (length > 1f + BOUNDS_TOLERANCE) fail("count $count — cell $index is at $position, length $length")
            }
        }
    }

    @Test
    fun `the avatar is deterministic and holds its sample while count and gaze are unchanged`() {
        val avatar = Avatar()
        val first = avatar.sample(80, LumosLook.BASE, 0f)
        assertSame(first, avatar.sample(80, LumosLook.BASE, 12.5f), "rebuilt for an unchanged count")
        assertSame(
            first,
            avatar.sample(80, LumosLook.BASE, 0f, Vector2(0.02f, -0.03f)),
            "rebuilt for a gaze inside one bucket",
        )
        assertNotSame(first, avatar.sample(81, LumosLook.BASE, 0f), "returned an 80-cell sample for 81")
        assertEquals(first.positions, Avatar().sample(80, LumosLook.BASE, 99f).positions, "two avatars differ")
    }

    @Test
    fun `no body cell sits inside an eye on either side of the orb`() {
        val avatar = Avatar()
        val radius = sin(Avatar.EYE_RADIUS) * HOLE_TOLERANCE
        for (count in COUNTS) {
            val sample = avatar.sample(count, LumosLook.BASE, 0f)
            val eyeCells = avatar.eyeCellCount(count)
            for (index in eyeCells until count) {
                val position = sample.positions[index]
                for ((centreX, centreY) in EYE_CENTRES) {
                    val dx = position.x - centreX
                    val dy = position.y - centreY
                    if (dx * dx + dy * dy < radius * radius) {
                        fail("count $count — body cell $index at $position is inside the eye at ($centreX, $centreY)")
                    }
                }
            }
        }
    }

    @Test
    fun `the avatar is rigid by default and can wear the skin`() {
        assertTrue(Avatar().rigid)
        assertTrue(!Avatar(rigid = false).rigid)
    }

    @Test
    fun `the eye rims take a share of the count that falls as the count grows`() {
        val avatar = Avatar()
        val inline = avatar.eyeCellCount(80) / 80f
        val hero = avatar.eyeCellCount(4801) / 4801f
        assertTrue(inline in 0.08f..0.20f, "inline share $inline")
        assertTrue(hero < inline, "hero share $hero should be below the inline share $inline")
        assertEquals(0, avatar.eyeCellCount(8), "too few cells for a face")
    }

    @Test
    fun `the gaze slides the eye cells toward the pointer`() {
        val avatar = Avatar()
        val still = avatar.sample(400, LumosLook.BASE, 0f)
        val right = avatar.sample(400, LumosLook.BASE, 0f, Vector2(1f, 0f))
        val eyeCells = avatar.eyeCellCount(400)
        val stillX = (0 until eyeCells).map { still.positions[it].x }.average()
        val rightX = (0 until eyeCells).map { right.positions[it].x }.average()
        assertTrue(rightX > stillX, "eyes at $rightX should sit right of $stillX")
    }

    @Test
    fun `tension squashes the body in y`() {
        val tall = Avatar().sample(400, LumosLook.BASE, 0f)
        val squashed = Avatar(tension = 1f).sample(400, LumosLook.BASE, 0f)
        val tallTop = tall.positions.maxOf { it.y }
        val squashedTop = squashed.positions.maxOf { it.y }
        assertEquals(tallTop * (1f - Avatar.TENSION_SQUASH), squashedTop, 1e-4f)
    }

    @Test
    fun `an avatar rejects a count below one and bad parameters`() {
        assertFailsWith<IllegalArgumentException> { Avatar().sample(0, LumosLook.BASE, 0f) }
        assertFailsWith<IllegalArgumentException> { Avatar(tension = 1.5f) }
        assertFailsWith<IllegalArgumentException> { Avatar(eyeScale = 0f) }
    }

    private companion object {
        val COUNTS = listOf(1, 8, 80, 400, 1_141, 4_801)
        const val BOUNDS_TOLERANCE = 1e-4f

        // Inside this fraction of the eye radius is the hole proper; the rest is the rim's margin.
        const val HOLE_TOLERANCE = 0.95f

        // The eyes' centres on the face, in screen space: `x` across, `y` up.
        val EYE_CENTRES =
            listOf(-1f, 1f).map { side ->
                (side * sin(Avatar.EYE_YAW) * cos(Avatar.EYE_PITCH)) to sin(Avatar.EYE_PITCH)
            }
    }
}
