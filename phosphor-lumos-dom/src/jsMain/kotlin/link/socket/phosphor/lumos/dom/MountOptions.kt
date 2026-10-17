package link.socket.phosphor.lumos.dom

import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.SurfaceStyle

/**
 * The options object a page hands [mount], as JavaScript sees it: a plain object, every field
 * optional. Each field is read as whatever the page put there and checked in [MountOptions.from],
 * so a number given as a string — straight from a query string or a data attribute — still works.
 *
 * - `style`: `"squares"` (the default), `"dots"` or `"dashes"`.
 * - `tint`: `"cell"` (the default), each cell its own colour, or `"currentColor"`, every cell in
 *   the CSS `color` of the `<svg>`.
 * - `atmosphere`: a preset name, `"idle"` by default; see `AtmospherePresets.ALL`.
 * - `look`: a look to start in, `"working"` or with a variant, `"working-gyro"`; `"base"` by default.
 * - `resolution`: the lattice resolution, a whole number from 1; the preset's own (10) by default.
 *   4 or less for an orb a few tens of pixels across.
 * - `speed`: 0.1 to 3, 1 by default.
 * - `dotSize`: multiplies every cell's radius; above 0, 1 by default.
 * - `reduceMotion`: hold still even if the platform does not ask; false by default.
 * - `label`: what the orb means, for screen readers; without one the orb is hidden from them.
 * - `maxFps`: the most frames a second it draws, 60 by default; 0 draws every animation frame.
 * - `halo`: draw the halo; true by default.
 * - `glyph`: draw the glyph overlay; true by default.
 * - `paused`: start paused; false by default.
 */
external interface LumosMountOptions {
    val style: Any?
    val tint: Any?
    val atmosphere: Any?
    val look: Any?
    val resolution: Any?
    val speed: Any?
    val dotSize: Any?
    val reduceMotion: Any?
    val label: Any?
    val maxFps: Any?
    val halo: Any?
    val glyph: Any?
    val paused: Any?
}

/** [LumosMountOptions] read and checked; see it for each field. */
internal data class MountOptions(
    val style: SurfaceStyle = SurfaceStyle.Squares,
    val tint: SvgTint = SvgTint.PER_CELL,
    val atmosphere: String = "idle",
    val look: Look = Look(LumosLook.BASE),
    val resolution: Int? = null,
    val speed: Float = 1f,
    val dotSize: Float = 1f,
    val reduceMotion: Boolean = false,
    val label: String? = null,
    val maxFps: Double = DEFAULT_MAX_FPS,
    val halo: Boolean = true,
    val glyph: Boolean = true,
    val paused: Boolean = false,
) {
    init {
        require(resolution == null || resolution >= 1) { "resolution must be a whole number from 1, was $resolution" }
        require(maxFps >= 0.0) { "maxFps must be >= 0, was $maxFps" }
    }

    /** The knobs the stepper reads. Validates speed and dot size. */
    val config: LumosConfig =
        LumosConfig(
            speed = speed,
            dotSize = dotSize,
            reducedMotion = reduceMotion,
            label = label,
            style = style,
            paused = paused,
        )

    companion object {
        const val DEFAULT_MAX_FPS: Double = 60.0

        /**
         * Read a page's options; null or undefined is every default.
         *
         * @throws IllegalArgumentException naming the field, for a value that is not one it takes.
         */
        fun from(options: LumosMountOptions?): MountOptions {
            if (options == null) return MountOptions()
            val defaults = MountOptions()
            return MountOptions(
                style = text(options.style, "style")?.let(::parseStyle) ?: defaults.style,
                tint = text(options.tint, "tint")?.let(::parseTint) ?: defaults.tint,
                atmosphere = text(options.atmosphere, "atmosphere") ?: defaults.atmosphere,
                look = text(options.look, "look")?.let(::parseLook) ?: defaults.look,
                resolution = number(options.resolution, "resolution")?.let { whole(it, "resolution") },
                speed = number(options.speed, "speed")?.toFloat() ?: defaults.speed,
                dotSize = number(options.dotSize, "dotSize")?.toFloat() ?: defaults.dotSize,
                reduceMotion = flag(options.reduceMotion, "reduceMotion") ?: defaults.reduceMotion,
                label = text(options.label, "label")?.takeIf { it.isNotBlank() },
                maxFps = number(options.maxFps, "maxFps") ?: defaults.maxFps,
                halo = flag(options.halo, "halo") ?: defaults.halo,
                glyph = flag(options.glyph, "glyph") ?: defaults.glyph,
                paused = flag(options.paused, "paused") ?: defaults.paused,
            )
        }
    }
}

/** `"squares"`, `"dots"` or `"dashes"`, any case. */
internal fun parseStyle(name: String): SurfaceStyle =
    when (name.trim().lowercase()) {
        "squares" -> SurfaceStyle.Squares
        "dots" -> SurfaceStyle.Dots
        "dashes" -> SurfaceStyle.Dashes
        else -> throw IllegalArgumentException("Unknown style \"$name\"; one of squares, dots, dashes")
    }

/** `"cell"` or `"currentColor"`, any case. */
internal fun parseTint(name: String): SvgTint =
    when (name.trim().lowercase()) {
        "cell" -> SvgTint.PER_CELL
        "currentcolor" -> SvgTint.CURRENT_COLOR
        else -> throw IllegalArgumentException("Unknown tint \"$name\"; one of cell, currentColor")
    }

/** A look by its clock-key name, any case: `"working"`, or `"working-gyro"` for a variant. */
internal fun parseLook(name: String): Look {
    val key = name.trim().lowercase()
    val dash = key.indexOf('-')
    val head = if (dash < 0) key else key.substring(0, dash)
    val variant = if (dash < 0) Look.DEFAULT_VARIANT else key.substring(dash + 1)
    val look =
        LumosLook.entries.firstOrNull { it.name.lowercase() == head }
            ?: throw IllegalArgumentException(
                "Unknown look \"$name\"; one of ${LumosLook.entries.joinToString { it.name.lowercase() }}",
            )
    require(variant in look.variants) {
        "Look \"$head\" has no variant \"$variant\"; it offers ${look.variants.joinToString()}"
    }
    return Look(look, variant)
}

private fun text(
    value: Any?,
    field: String,
): String? =
    when (value) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> throw IllegalArgumentException("$field must be a string, was $value")
    }

private fun number(
    value: Any?,
    field: String,
): Double? =
    when (value) {
        null -> null
        is Number -> value.toDouble()
        is String ->
            value.trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("$field must be a number, was \"$value\"")
        else -> throw IllegalArgumentException("$field must be a number, was $value")
    }.also { require(it == null || it.isFinite()) { "$field must be finite, was $it" } }

private fun whole(
    value: Double,
    field: String,
): Int {
    require(value % 1.0 == 0.0) { "$field must be a whole number, was $value" }
    return value.toInt()
}

private fun flag(
    value: Any?,
    field: String,
): Boolean? =
    when (value) {
        null -> null
        is Boolean -> value
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$field must be true or false, was $value")
    }
