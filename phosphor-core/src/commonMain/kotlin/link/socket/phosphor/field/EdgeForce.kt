package link.socket.phosphor.field

import link.socket.phosphor.math.Vector2

/**
 * Transfer function used by an [EdgeForce].
 */
fun interface EdgeTransferFunction {
    fun transfer(
        from: Volume,
        to: Volume,
        deltaTime: Float,
    ): Float
}

/**
 * Fluid coupling between two adjacent volumes.
 *
 * The transfer function returns "requested transfer amount per step".
 * The actual transfer is clamped to available fluid in the source volume.
 */
data class EdgeForce(
    val fromVolumeId: String,
    val toVolumeId: String,
    val transferFunction: EdgeTransferFunction = DEFAULT_TRANSFER_FUNCTION,
    val momentumScale: Float = DEFAULT_MOMENTUM_SCALE,
) {
    init {
        require(fromVolumeId.isNotBlank()) { "fromVolumeId must not be blank" }
        require(toVolumeId.isNotBlank()) { "toVolumeId must not be blank" }
        require(fromVolumeId != toVolumeId) { "fromVolumeId and toVolumeId must be different" }
        require(momentumScale >= 0f) { "momentumScale must be >= 0, got $momentumScale" }
    }

    internal fun apply(
        world: PhosphorWorld,
        deltaTime: Float,
    ) {
        val from = world.getVolume(fromVolumeId) ?: return
        val to = world.getVolume(toVolumeId) ?: return

        if (!from.isAdjacentTo(to)) return

        val requestedAmount = transferFunction.transfer(from, to, deltaTime).coerceAtLeast(0f)
        if (requestedAmount <= 0f) return

        val moved = from.removeFluid(requestedAmount)
        if (moved <= 0f) return

        to.addFluid(moved)
        transferMomentum(from = from, to = to, transferredAmount = moved)
    }

    private fun transferMomentum(
        from: Volume,
        to: Volume,
        transferredAmount: Float,
    ) {
        val direction =
            Vector2(
                x = to.bounds.centerX - from.bounds.centerX,
                y = to.bounds.centerY - from.bounds.centerY,
            ).normalized()

        if (direction.length() == 0f) return

        val impulse = direction * (transferredAmount * momentumScale)
        from.applyImpulse(impulse * -1f)
        to.applyImpulse(impulse)
    }

    companion object {
        const val DEFAULT_MOMENTUM_SCALE: Float = 0.1f

        val DEFAULT_TRANSFER_FUNCTION =
            EdgeTransferFunction { from, to, deltaTime ->
                val pressureDelta = (from.pressure - to.pressure).coerceAtLeast(0f)
                if (pressureDelta == 0f) {
                    0f
                } else {
                    val coupling = minOf(from.fluidType.diffusionRate, to.fluidType.diffusionRate)
                    pressureDelta * coupling * deltaTime
                }
            }
    }
}
