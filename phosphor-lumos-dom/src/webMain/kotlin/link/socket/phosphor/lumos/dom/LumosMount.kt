package link.socket.phosphor.lumos.dom

import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Options for [mount], parsed from `key=value&key=value` so neither target needs a JSON
 * library or a typed JS object: the JS shim flattens a plain object into this form.
 */
class MountOptions(
    val style: SurfaceStyle = SurfaceStyle.Dots,
    val tint: SvgOrbSurface.Tint = SvgOrbSurface.Tint.PER_CELL,
    val atmosphere: String = "idle",
    val resolution: Int? = null,
    val speed: Float = 1f,
    val dotSize: Float = 1f,
    val reduceMotion: Boolean = false,
    val label: String? = null,
    val sampleCapacity: Int = 2_000,
) {
    companion object {
        fun parse(encoded: String): MountOptions {
            val map = HashMap<String, String>()
            for (pair in encoded.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) map[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            return MountOptions(
                style = if (map["style"]?.lowercase() == "squares") SurfaceStyle.Squares else SurfaceStyle.Dots,
                tint =
                    if (map["tint"]?.lowercase() == "currentcolor") {
                        SvgOrbSurface.Tint.CURRENT_COLOR
                    } else {
                        SvgOrbSurface.Tint.PER_CELL
                    },
                atmosphere = map["atmosphere"] ?: "idle",
                resolution = map["resolution"]?.toIntOrNull(),
                speed = map["speed"]?.toFloatOrNull() ?: 1f,
                dotSize = map["dotSize"]?.toFloatOrNull() ?: 1f,
                reduceMotion = map["reduceMotion"] == "true",
                label = map["label"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** Live orbs by handle; the plain-JS API hands integers across, which both targets export easily. */
object LumosMounts {
    private val drivers = HashMap<Int, DomLumosDriver>()
    private var nextHandle = 1

    fun mount(
        svg: WebElement,
        encodedOptions: String,
    ): Int {
        val options = MountOptions.parse(encodedOptions)
        val preset = AtmospherePresets.byName(options.atmosphere) ?: AtmospherePresets.IDLE
        val atmosphere = options.resolution?.let { preset.copy(resolution = it) } ?: preset
        val source = LiveFrameSource(atmosphere)
        val surface = SvgOrbSurface(svg, options.style, options.tint)
        if (options.label != null) {
            svg.setAttribute("role", "img")
            svg.setAttribute("aria-label", options.label)
        } else {
            svg.setAttribute("aria-hidden", "true")
        }
        val driver = DomLumosDriver(source, surface, svg, options)
        val handle = nextHandle++
        drivers[handle] = driver
        driver.start()
        return handle
    }

    fun unmount(handle: Int) {
        drivers.remove(handle)?.stop()
    }

    fun stats(handle: Int): String = drivers[handle]?.statsJson() ?: "null"
}

fun main() {
    installGlobalApi()
}
