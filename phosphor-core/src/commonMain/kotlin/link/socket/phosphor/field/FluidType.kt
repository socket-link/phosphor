package link.socket.phosphor.field

/**
 * Defines physical behavior for a fluid family.
 *
 * Each fluid type controls how quickly momentum dissipates (viscosity),
 * how much pressure it produces for a given amount (density), and how
 * quickly it spreads through edges (diffusionRate).
 */
sealed class FluidType(
    val viscosity: Float,
    val density: Float,
    val diffusionRate: Float,
    val particleType: ParticleType,
) {
    init {
        require(viscosity >= 0f) { "viscosity must be >= 0, got $viscosity" }
        require(density > 0f) { "density must be > 0, got $density" }
        require(diffusionRate >= 0f) { "diffusionRate must be >= 0, got $diffusionRate" }
    }

    /**
     * Medium viscosity, balanced spread, stable pressure.
     */
    object Water : FluidType(
        viscosity = 0.08f,
        density = 1.0f,
        diffusionRate = 0.35f,
        particleType = ParticleType.MOTE,
    )

    /**
     * Low viscosity, fast spread, low density.
     */
    object Fire : FluidType(
        viscosity = 0.02f,
        density = 0.35f,
        diffusionRate = 0.7f,
        particleType = ParticleType.SPARK,
    )

    /**
     * Very low viscosity, broad diffusion, light pressure.
     */
    object Air : FluidType(
        viscosity = 0.01f,
        density = 0.12f,
        diffusionRate = 0.9f,
        particleType = ParticleType.TRAIL,
    )

    /**
     * User-defined fluid profile.
     */
    class Custom(
        val name: String,
        viscosity: Float,
        density: Float,
        diffusionRate: Float,
        particleType: ParticleType = ParticleType.MOTE,
    ) : FluidType(
            viscosity = viscosity,
            density = density,
            diffusionRate = diffusionRate,
            particleType = particleType,
        ) {
        init {
            require(name.isNotBlank()) { "name must not be blank" }
        }
    }
}
