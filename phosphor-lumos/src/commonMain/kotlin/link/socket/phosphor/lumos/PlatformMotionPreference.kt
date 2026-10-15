package link.socket.phosphor.lumos

/**
 * The platform's request for less motion.
 *
 * An orb driver ORs it with [LumosConfig.reducedMotion], so the platform always wins: no config
 * can switch reduced motion off for someone who asked their device for it.
 *
 * - iOS reads `UIAccessibility.isReduceMotionEnabled`.
 * - Web reads `matchMedia("(prefers-reduced-motion: reduce)")`, and reads false outside a browser.
 * - The JVM reads false: no desktop toolkit hands the setting to the JVM.
 * - Android reads false for now: its setting, "Remove animations", is only reachable through a
 *   `Context`.
 */
object PlatformMotionPreference {
    /** Whether the platform asks for reduced motion right now. It can change while the app runs. */
    val reduceMotion: Boolean get() = platformReducesMotion()
}

/** Each platform's answer for [PlatformMotionPreference.reduceMotion]. */
internal expect fun platformReducesMotion(): Boolean
