package link.socket.phosphor.field

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.math.Vector2

/**
 * Axis-aligned region in world space where fluid simulation is computed.
 */
data class VolumeBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
        require(height > 0) { "height must be > 0, got $height" }
    }

    val maxXExclusive: Int = x + width
    val maxYExclusive: Int = y + height
    val centerX: Float = x + width * 0.5f
    val centerY: Float = y + height * 0.5f

    fun contains(
        px: Int,
        py: Int,
    ): Boolean {
        return px >= x && px < maxXExclusive && py >= y && py < maxYExclusive
    }

    /**
     * Returns true when edges touch with overlap on the perpendicular axis.
     * Diagonal corner contact does not count as adjacent.
     */
    fun isAdjacentTo(other: VolumeBounds): Boolean {
        val horizontalTouch = maxXExclusive == other.x || other.maxXExclusive == x
        val verticalOverlap = y < other.maxYExclusive && other.y < maxYExclusive

        val verticalTouch = maxYExclusive == other.y || other.maxYExclusive == y
        val horizontalOverlap = x < other.maxXExclusive && other.x < maxXExclusive

        return (horizontalTouch && verticalOverlap) || (verticalTouch && horizontalOverlap)
    }
}

/**
 * Mutable simulation state for one spatial region.
 */
class Volume(
    val id: String,
    val bounds: VolumeBounds,
    val fluidType: FluidType,
    initialAmount: Float = 0f,
    initialVelocity: Vector2 = Vector2.ZERO,
    val particleBudget: Int = DEFAULT_PARTICLE_BUDGET,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(particleBudget >= 0) { "particleBudget must be >= 0, got $particleBudget" }
    }

    private val particleSystem =
        ParticleSystem(
            maxParticles = particleBudget,
            drag = fluidType.viscosity,
            gravity = Vector2.ZERO,
            lifeDecayRate = 0f,
        )

    var amount: Float = initialAmount.coerceAtLeast(0f)
        private set

    var velocity: Vector2 = initialVelocity
        private set

    var pressure: Float = amount * fluidType.density
        private set

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = bounds.contains(x, y)

    fun isAdjacentTo(other: Volume): Boolean = bounds.isAdjacentTo(other.bounds)

    fun setFluidAmount(newAmount: Float) {
        amount = newAmount.coerceAtLeast(0f)
        recomputePressure()
    }

    fun addFluid(delta: Float) {
        if (delta <= 0f) return
        amount += delta
        recomputePressure()
    }

    fun removeFluid(delta: Float): Float {
        if (delta <= 0f || amount <= 0f) return 0f
        val removed = delta.coerceAtMost(amount)
        amount -= removed
        recomputePressure()
        return removed
    }

    fun applyImpulse(impulse: Vector2) {
        velocity = velocity + impulse
    }

    fun renderParticles(): List<Particle> = particleSystem.getParticles()

    internal fun integrate(deltaTime: Float) {
        val damping = (1f - fluidType.viscosity * deltaTime).coerceIn(0f, 1f)
        velocity = velocity * damping
        recomputePressure()
    }

    internal fun syncParticleOutput(simulationTime: Float) {
        particleSystem.clear()

        if (amount <= 0f || particleBudget == 0) return

        val targetParticles = (amount * particleBudget).toInt().coerceIn(0, particleBudget)
        if (targetParticles == 0) return

        val particles =
            List(targetParticles) { index ->
                createParticle(index = index, simulationTime = simulationTime)
            }
        particleSystem.addParticles(particles)
    }

    private fun createParticle(
        index: Int,
        simulationTime: Float,
    ): Particle {
        val position = latticePosition(index = index, simulationTime = simulationTime)
        val glyph = Particle.Companion.Glyphs.forType(fluidType.particleType, life = 1f, useUnicode = true)

        return Particle(
            position = position,
            velocity = velocity * fluidType.diffusionRate,
            life = 1f,
            type = fluidType.particleType,
            glyph = glyph,
        )
    }

    private fun latticePosition(
        index: Int,
        simulationTime: Float,
    ): Vector2 {
        val columns = max(1, sqrt(particleBudget.toFloat()).toInt())
        val rows = max(1, ceil(particleBudget.toFloat() / columns).toInt())

        val col = index % columns
        val row = (index / columns) % rows

        val baseX = bounds.x + (col + 0.5f) / columns * bounds.width
        val baseY = bounds.y + (row + 0.5f) / rows * bounds.height

        // Mild deterministic drift to keep the field visually alive.
        val phase = simulationTime * fluidType.diffusionRate + index * 0.41f
        val driftX = sin(phase) * 0.25f
        val driftY = cos(phase) * 0.25f

        val maxX = (bounds.maxXExclusive - 1).toFloat()
        val maxY = (bounds.maxYExclusive - 1).toFloat()

        return Vector2(
            x = (baseX + driftX).coerceIn(bounds.x.toFloat(), maxX),
            y = (baseY + driftY).coerceIn(bounds.y.toFloat(), maxY),
        )
    }

    private fun recomputePressure() {
        pressure = amount * fluidType.density
    }

    companion object {
        const val DEFAULT_PARTICLE_BUDGET: Int = 48
    }
}
