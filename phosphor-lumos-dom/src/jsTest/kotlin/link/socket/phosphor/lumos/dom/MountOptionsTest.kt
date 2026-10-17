package link.socket.phosphor.lumos.dom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.SurfaceStyle

/** What a page may hand `mount`, and what it is told when it hands it something else. */
class MountOptionsTest {
    @Test
    fun `no options is every default`() {
        assertEquals(MountOptions(), MountOptions.from(null))
        assertEquals(MountOptions(), MountOptions.from(options(js("({})"))))
        assertEquals(SurfaceStyle.Squares, MountOptions().style)
        assertEquals(60.0, MountOptions().maxFps)
    }

    @Test
    fun `every field is read from a plain object`() {
        val read =
            MountOptions.from(
                options(
                    js(
                        "({ style: 'Dashes', tint: 'currentColor', atmosphere: 'thinking', look: 'working-gyro', " +
                            "resolution: 3, speed: 2, dotSize: 0.5, reduceMotion: true, label: 'Lumos', maxFps: 30, " +
                            "halo: false, glyph: false, paused: true })",
                    ),
                ),
            )

        assertEquals(
            MountOptions(
                style = SurfaceStyle.Dashes,
                tint = SvgTint.CURRENT_COLOR,
                atmosphere = "thinking",
                look = Look(LumosLook.WORKING, "gyro"),
                resolution = 3,
                speed = 2f,
                dotSize = 0.5f,
                reduceMotion = true,
                label = "Lumos",
                maxFps = 30.0,
                halo = false,
                glyph = false,
                paused = true,
            ),
            read,
        )
    }

    @Test
    fun `numbers and flags given as strings, as a query string gives them, are read too`() {
        val read =
            MountOptions.from(options(js("({ resolution: '4', speed: '0.5', reduceMotion: 'true', label: '' })")))

        assertEquals(4, read.resolution)
        assertEquals(0.5f, read.speed)
        assertEquals(true, read.reduceMotion)
        assertEquals(null, read.label, "an empty label is no label")
    }

    @Test
    fun `a value a field does not take is refused, naming the field`() {
        // Pair, not `to`: an extension called on a dynamic receiver is dispatched dynamically.
        val refusals =
            listOf<Pair<dynamic, String>>(
                Pair(js("({ style: 'hexagons' })"), "style"),
                Pair(js("({ tint: 'blue' })"), "tint"),
                Pair(js("({ look: 'dancing' })"), "look"),
                Pair(js("({ look: 'base-gyro' })"), "gyro"),
                Pair(js("({ resolution: 0 })"), "resolution"),
                Pair(js("({ resolution: 2.5 })"), "resolution"),
                Pair(js("({ speed: 'fast' })"), "speed"),
                Pair(js("({ speed: 5 })"), "speed"),
                Pair(js("({ dotSize: 0 })"), "dotSize"),
                Pair(js("({ maxFps: -1 })"), "maxFps"),
                Pair(js("({ halo: 'sometimes' })"), "halo"),
            )
        for ((value, field) in refusals) {
            val error = assertFailsWith<IllegalArgumentException> { MountOptions.from(options(value)) }
            assertTrue(error.message.orEmpty().contains(field), "${error.message} should name $field")
        }
    }

    @Test
    fun `an unknown atmosphere is refused when the orb is mounted`() {
        val error = assertFailsWith<IllegalArgumentException> { MountedScene("sleepy", resolution = null) }

        assertTrue(error.message.orEmpty().contains("idle, listening, thinking, uncertain, ready"))
    }

    @Test
    fun `looks are named as their clock keys, any case`() {
        assertEquals(Look(LumosLook.WORKING), parseLook("working"))
        assertEquals(Look(LumosLook.SEARCHING, "lighthouse"), parseLook("Searching-Lighthouse"))
        assertEquals(Look(LumosLook.BASE), parseLook(" BASE "))
    }

    private fun options(value: dynamic): LumosMountOptions = value.unsafeCast<LumosMountOptions>()
}
