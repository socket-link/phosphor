package link.socket.phosphor.emitter

/**
 * Well-known metadata keys for generic emitter modulation.
 *
 * These remain domain-neutral so bridges can map their own numeric signals
 * onto common visual semantics without leaking source concepts into Phosphor.
 */
object MetadataKeys {
    /** Overall intensity multiplier. Scales an effect's peak intensity. */
    const val INTENSITY = "phosphor.intensity"

    /** Thermal energy. Higher values can bias effects toward hotter, brighter behavior. */
    const val HEAT = "phosphor.heat"

    /** Density multiplier for effects that vary their particle or fragment count. */
    const val DENSITY = "phosphor.density"

    /** Duration multiplier for effects that stretch or compress their lifespan. */
    const val DURATION_SCALE = "phosphor.duration_scale"

    /** Radius multiplier for effects that widen or tighten spatial reach. */
    const val RADIUS_SCALE = "phosphor.radius_scale"
}
