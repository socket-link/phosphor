package link.socket.phosphor.renderer

import link.socket.phosphor.color.ColorRamp
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.PlatformColorAdapter

/**
 * Default adapter from NeutralColor to ComposeRenderer's platform color model.
 */
class ComposeColorAdapter : PlatformColorAdapter<ComposeColor> {
    override fun adapt(color: NeutralColor): ComposeColor {
        return ComposeColor(
            red = color.redInt,
            green = color.greenInt,
            blue = color.blueInt,
            alpha = color.alpha,
        )
    }

    fun adapt(ramp: ColorRamp): List<ComposeColor> = ramp.stops.map(::adapt)
}
