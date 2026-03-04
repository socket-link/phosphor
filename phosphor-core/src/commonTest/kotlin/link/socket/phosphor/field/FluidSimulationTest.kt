package link.socket.phosphor.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FLOAT_TOLERANCE = 1e-6f

class FluidTypeTest {
    @Test
    fun `custom fluid validates parameters`() {
        assertFailsWith<IllegalArgumentException> {
            FluidType.Custom(
                name = "custom",
                viscosity = -0.1f,
                density = 1f,
                diffusionRate = 0.2f,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            FluidType.Custom(
                name = "custom",
                viscosity = 0.1f,
                density = 0f,
                diffusionRate = 0.2f,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            FluidType.Custom(
                name = "custom",
                viscosity = 0.1f,
                density = 1f,
                diffusionRate = -1f,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            FluidType.Custom(
                name = "   ",
                viscosity = 0.1f,
                density = 1f,
                diffusionRate = 0.2f,
            )
        }
    }

    @Test
    fun `preset fluids expose distinct behavior profiles`() {
        assertTrue(FluidType.Fire.diffusionRate > FluidType.Water.diffusionRate)
        assertTrue(FluidType.Water.density > FluidType.Air.density)
        assertTrue(FluidType.Air.viscosity < FluidType.Water.viscosity)
    }
}

class EdgeForceTest {
    @Test
    fun `edge force transfers fluid between adjacent volumes`() {
        val world = PhosphorWorld(width = 20, height = 10, fixedTimeStep = 0.1f)
        val left =
            Volume(
                id = "left",
                bounds = VolumeBounds(x = 0, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
                initialAmount = 1f,
            )
        val right =
            Volume(
                id = "right",
                bounds = VolumeBounds(x = 10, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
                initialAmount = 0f,
            )

        world.addVolume(left)
        world.addVolume(right)
        world.addEdgeForce(EdgeForce(fromVolumeId = "left", toVolumeId = "right"))

        world.step(0.1f)

        assertTrue(left.amount < 1f, "Source volume should lose fluid")
        assertTrue(right.amount > 0f, "Target volume should gain fluid")
        assertTrue(right.velocity.x > 0f, "Target volume should receive positive x impulse")
    }

    @Test
    fun `edge force does not transfer across non-adjacent volumes`() {
        val world = PhosphorWorld(width = 30, height = 10, fixedTimeStep = 0.1f)
        val left =
            Volume(
                id = "left",
                bounds = VolumeBounds(x = 0, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
                initialAmount = 1f,
            )
        val right =
            Volume(
                id = "right",
                bounds = VolumeBounds(x = 11, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
                initialAmount = 0f,
            )

        world.addVolume(left)
        world.addVolume(right)
        world.addEdgeForce(EdgeForce(fromVolumeId = "left", toVolumeId = "right"))

        world.step(0.1f)

        assertEquals(1f, left.amount, FLOAT_TOLERANCE)
        assertEquals(0f, right.amount, FLOAT_TOLERANCE)
    }
}

class PhosphorWorldTest {
    @Test
    fun `world indexes volumes in spatial partitions`() {
        val world = PhosphorWorld(width = 64, height = 32, partitionSize = 8)
        val volume =
            Volume(
                id = "v1",
                bounds = VolumeBounds(x = 12, y = 4, width = 10, height = 6),
                fluidType = FluidType.Air,
                initialAmount = 0.2f,
            )

        world.addVolume(volume)

        val found = world.volumesAt(x = 15, y = 7)
        val missing = world.volumesAt(x = 40, y = 20)

        assertEquals(1, found.size)
        assertEquals("v1", found.first().id)
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `update uses fixed timestep and interleaved solver passes`() {
        val world = PhosphorWorld(width = 20, height = 10, fixedTimeStep = 0.1f)
        world.addVolume(
            Volume(
                id = "core",
                bounds = VolumeBounds(x = 0, y = 0, width = 20, height = 10),
                fluidType = FluidType.Water,
                initialAmount = 0.4f,
            ),
        )

        val solverA = RecordingSolver()
        val solverB = RecordingSolver()
        world.setSolvers(listOf(solverA, solverB))

        val steps = world.update(0.25f)

        assertEquals(2, steps)
        assertEquals(2, solverA.calls)
        assertEquals(2, solverB.calls)
        assertEquals(0.05f, solverA.deltaTimes.first(), FLOAT_TOLERANCE)
        assertEquals(0.05f, solverB.deltaTimes.first(), FLOAT_TOLERANCE)
        assertEquals(0.2f, world.simulationTime, FLOAT_TOLERANCE)
    }

    @Test
    fun `connectAdjacentVolumes creates bidirectional edges once`() {
        val world = PhosphorWorld(width = 20, height = 10)
        world.addVolume(
            Volume(
                id = "left",
                bounds = VolumeBounds(x = 0, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
            ),
        )
        world.addVolume(
            Volume(
                id = "right",
                bounds = VolumeBounds(x = 10, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
            ),
        )

        val first = world.connectAdjacentVolumes()
        val second = world.connectAdjacentVolumes()

        assertEquals(2, first)
        assertEquals(0, second)
        assertEquals(2, world.edgeForceCount)
    }

    @Test
    fun `particle output is derived from fluid state`() {
        val world = PhosphorWorld(width = 20, height = 10, fixedTimeStep = 0.1f)
        world.addVolume(
            Volume(
                id = "left",
                bounds = VolumeBounds(x = 0, y = 0, width = 10, height = 10),
                fluidType = FluidType.Water,
                initialAmount = 1f,
                particleBudget = 12,
            ),
        )

        world.step(0.1f)
        val particles = world.toParticleSystem(maxParticles = 12).getParticles()

        assertEquals(12, particles.size)
        assertTrue(particles.all { particle -> particle.type == ParticleType.MOTE })
    }

    @Test
    fun `adding out-of-bounds volume fails`() {
        val world = PhosphorWorld(width = 10, height = 10)
        val invalid =
            Volume(
                id = "invalid",
                bounds = VolumeBounds(x = 8, y = 8, width = 5, height = 5),
                fluidType = FluidType.Water,
            )

        assertFailsWith<IllegalArgumentException> {
            world.addVolume(invalid)
        }
    }
}

private class RecordingSolver : Solver {
    var calls: Int = 0
        private set
    val deltaTimes = mutableListOf<Float>()

    override fun solve(
        world: PhosphorWorld,
        deltaTime: Float,
    ) {
        calls++
        deltaTimes.add(deltaTime)
    }
}
