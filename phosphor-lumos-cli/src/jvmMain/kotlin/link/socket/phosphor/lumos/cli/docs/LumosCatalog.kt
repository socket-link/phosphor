package link.socket.phosphor.lumos.cli.docs

import kotlinx.serialization.KSerializer
import link.socket.phosphor.lumos.CognitiveKey
import link.socket.phosphor.lumos.CognitiveLook
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.FramedForm
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.counted
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState

/**
 * Every public Lumos knob an integrating agent needs, read from the catalogues themselves: the
 * model `docs/lumos/llms.txt` and `skills/lumos/` are rendered from.
 *
 * Names, defaults, framing, periods, the phase → look table and whether a look is drawn yet all
 * come from code. Only the one-line meanings are written here, in exhaustive `when`s over the
 * catalogue types and in tables keyed by name that fail the generator when an entry is missing,
 * so a new form, look, style, material, glyph, preset or config field cannot ship undocumented.
 */
internal object LumosCatalog {
    val config: List<ConfigRow> by lazy {
        fieldsOf(LumosConfig.serializer(), LumosConfig()).map { field ->
            val (meaning, constraint) =
                CONFIG_MEANINGS[field.name] ?: error("LumosConfig.${field.name} has no entry in CONFIG_MEANINGS")
            ConfigRow(field, meaning, constraint)
        }
    }

    val forms: List<FormRow> by lazy {
        Forms.All.map { (name, form) ->
            val framed = form as? FramedForm
            FormRow(
                name = name,
                kotlin = form.toString(),
                scale = framed?.scale ?: 1f,
                tipDegrees = framed?.tipDegrees ?: 0f,
                meaning = FORM_MEANINGS[name] ?: error("Forms.All has \"$name\" with no entry in FORM_MEANINGS"),
            )
        }
    }

    /** Cells in the default lattice at the resolutions worth quoting. */
    val latticeCounts: List<Pair<Int, Int>> by lazy {
        LATTICE_RESOLUTIONS.map { it to Forms.Lattice(it).count }
    }

    val looks: List<LookRow> by lazy {
        val base = framesWearing(Look(LumosLook.BASE))
        LumosLook.entries.flatMap { look ->
            val default = framesWearing(Look(look))
            look.variants.map { variant ->
                val worn = Look(look, variant)
                val drawn =
                    when {
                        look != LumosLook.BASE && default == base -> Drawn.AS_BASE
                        variant != Look.DEFAULT_VARIANT && framesWearing(worn) == default -> Drawn.AS_DEFAULT
                        else -> Drawn.YES
                    }
                LookRow(worn, drawn, if (variant == Look.DEFAULT_VARIANT) lookMeaning(look) else null)
            }
        }
    }

    val switchRows: List<Pair<CognitiveKey, CognitiveLook>>
        get() = CognitiveLookSwitch.DEFAULT.entries.map { it.toPair() }

    val atmospheres: List<AtmosphereRow> by lazy {
        AtmospherePresets.ALL.map { (name, state) ->
            AtmosphereRow(
                name = name,
                state = state,
                meaning = PRESET_MEANINGS[name] ?: error("preset \"$name\" has no entry in PRESET_MEANINGS"),
            )
        }
    }

    val patterns: List<Pair<AtmospherePattern, String>>
        get() = AtmospherePattern.entries.map { it to patternMeaning(it) }

    val styles: List<StyleRow> by lazy {
        STYLES.map { style -> StyleRow(style, style.toString(), styleMeaning(style)) }
    }

    /**
     * The materials the docs list, at their defaults. `AgentDocsTest` holds these to the
     * subclasses [Material]'s serializer knows, so a new material fails the build until it is
     * listed here.
     */
    val materials: List<MaterialRow> by lazy {
        listOf(
            materialRow(Material.Flat, Material.Flat.serializer()),
            materialRow(Material.Ink(), Material.Ink.serializer()),
            materialRow(Material.Neon(), Material.Neon.serializer()),
            materialRow(Material.Metal(), Material.Metal.serializer()),
            materialRow(Material.Glass(), Material.Glass.serializer()),
        )
    }

    val glyphs: List<Pair<LumosGlyph, String>> get() = LumosGlyph.entries.map { it to glyphMeaning(it) }

    /**
     * The styles the docs list, in catalogue order. `AgentDocsTest` holds them to the subclasses
     * [SurfaceStyle]'s serializer knows, so a new style fails the build until it is listed here.
     */
    private val STYLES: List<SurfaceStyle> = listOf(SurfaceStyle.Squares, SurfaceStyle.Dots, SurfaceStyle.Dashes)

    private val LATTICE_RESOLUTIONS = listOf(3, 6, 10)

    // Whether a look is drawn yet is measured, not declared: an unported look draws BASE's frames,
    // an unported variant its look's default. A small form keeps the reasoning walk's O(N²) table cheap.
    private fun framesWearing(look: Look): List<VoxelFrame> {
        val source = LiveFrameSource(AtmospherePresets.IDLE, LumosRenderConfig(rimFade = true))
        source.builder.form = Forms.Fibonacci.counted(PROBE_CELLS)
        source.builder.look = look
        return List(PROBE_FRAMES) { source.next(PROBE_DT).copy(tick = 0L, timestampEpochMillis = 0L) }
    }

    private const val PROBE_CELLS = 120
    private const val PROBE_FRAMES = 24
    private const val PROBE_DT = 0.25f

    private fun <T : Material> materialRow(
        material: T,
        serializer: KSerializer<T>,
    ): MaterialRow =
        MaterialRow(
            material = material,
            name = materialName(material),
            fields = fieldsOf(serializer, material),
            drawsYet = drawsYet(material),
            meaning = materialMeaning(material),
        )

    private fun materialName(material: Material): String =
        when (material) {
            Material.Flat -> "Flat"
            is Material.Ink -> "Ink"
            is Material.Neon -> "Neon"
            is Material.Metal -> "Metal"
            is Material.Glass -> "Glass"
        }

    private fun lookMeaning(look: LumosLook): String =
        when (look) {
            LumosLook.BASE -> "Idle, or anything without a look of its own. The atmosphere's own spin."
            LumosLook.WORKING -> "Busy, running a tool. A ring of light runs down the sphere."
            LumosLook.REASONING -> "Thinking. A lit walk hops between neighbouring cells, steered toward the viewer."
            LumosLook.SEARCHING ->
                "Searching the web or files. A lens hops between spots on the front of the sphere, " +
                    "magnifying what it covers."
            LumosLook.BACKGROUND -> "Background work nobody is watching, like a dev server."
            LumosLook.RETRYING ->
                "Retrying after an error. The orb turns, brakes, springs back 60 % of the turn and tries again; " +
                    "it owns the spin while worn."
            LumosLook.COMPACTING -> "Compacting the context window, or consolidating what was learned."
            LumosLook.WAITING -> "Waiting on a human: worn while an agent is escalated."
        }

    private fun patternMeaning(pattern: AtmospherePattern): String =
        when (pattern) {
            AtmospherePattern.LONGITUDE -> "Bands around the vertical axis."
            AtmospherePattern.LATITUDE -> "Bands around the polar axis."
            AtmospherePattern.SPIRAL -> "A combined sweep of both angles."
            AtmospherePattern.SCAN -> "A sweep along the vertical axis."
            AtmospherePattern.PLASMA -> "Three-axis noise."
            AtmospherePattern.PULSE -> "Rings out from the centre."
            AtmospherePattern.SOLID -> "No variation."
        }

    private fun styleMeaning(style: SurfaceStyle): String =
        when (style) {
            SurfaceStyle.Squares -> "A square per cell, two radii on a side. What every orb drew before styles existed."
            SurfaceStyle.Dots -> "A circle per cell."
            SurfaceStyle.Dashes ->
                "A round-capped stroke per cell along the spin, ${num(SurfaceStyle.DASH_HALF_LENGTH)} radii each " +
                    "way at full speed and ${num(SurfaceStyle.DASH_WIDTH)} radii wide, so a turning orb reads as " +
                    "motion even when small."
        }

    private fun materialMeaning(material: Material): String =
        when (material) {
            Material.Flat -> "The style's own mark."
            is Material.Ink ->
                "Paper grain: each cell's alpha and radius wander by `grain`, drawn as a soft edge under an " +
                    "opaque core."
            is Material.Neon ->
                "An emissive tube: a tinted envelope under a white filament, and the orb's halo widened by `glow`."
            is Material.Metal -> "Brushed metal lit from `lightDegrees`. Declared so the type can be written today."
            is Material.Glass -> "Refracting glass. Declared so the type can be written today."
        }

    // What LumosCanvas draws today; it reports MaterialStatus.Unavailable for the rest.
    private fun drawsYet(material: Material): Boolean =
        when (material) {
            Material.Flat, is Material.Ink, is Material.Neon -> true
            is Material.Metal, is Material.Glass -> false
        }

    /** The constraint a material's constructor enforces on [field], or null for none. */
    fun materialConstraint(
        material: String,
        field: String,
    ): String? = MATERIAL_CONSTRAINTS["$material.$field"]

    private val MATERIAL_CONSTRAINTS: Map<String, String> =
        mapOf(
            "Ink.grain" to "0..1",
            "Ink.intensity" to ">= 0",
            "Neon.glow" to "0..1",
            "Neon.intensity" to ">= 0",
        )

    private fun glyphMeaning(glyph: LumosGlyph): String =
        when (glyph) {
            LumosGlyph.CHECK -> "Done: a task completed."
            LumosGlyph.EXCLAIM -> "Attention required: a warning."
            LumosGlyph.QUESTION -> "Uncertain, or needs a human: fired when an agent escalates."
            LumosGlyph.HEART -> "Affirmation, or a preference noted."
            LumosGlyph.STAR -> "An achievement or milestone."
            LumosGlyph.LIGHTNING -> "Active execution: a spark."
        }

    private val FORM_MEANINGS: Map<String, String> =
        mapOf(
            "fibonacci" to
                "`count` points spread evenly over the sphere's surface on the golden-angle spiral. " +
                "The inline orb's form; any count from 1 works.",
            "cube" to
                "A cube inscribed in the sphere: the smallest `6n² + 2`-point surface grid that holds `count`, " +
                "thinned evenly to exactly `count`. At 8 it is the corners.",
        )

    private val PRESET_MEANINGS: Map<String, String> =
        mapOf(
            "idle" to "Nothing in flight.",
            "listening" to "Taking input: the person is speaking or typing.",
            "thinking" to "Working on it.",
            "uncertain" to "Unsure, or waiting on a human: two hues held apart.",
            "ready" to "Done, with something to show.",
        )

    // Meaning, then the constraint `LumosConfig`'s init enforces ("" for none).
    private val CONFIG_MEANINGS: Map<String, Pair<String, String>> =
        mapOf(
            "speed" to
                Pair(
                    "Multiplies every step of time the driver hands the source: breath, pattern, spin, " +
                        "transitions, glyph windows. An atmosphere's own rates never change.",
                    "${num(LumosConfig.MIN_SPEED)}..${num(LumosConfig.MAX_SPEED)}",
                ),
            "density" to
                Pair("Cell-count multiplier for forms whose count is free to vary. The lattice ignores it.", "> 0"),
            "dotSize" to Pair("Multiplies the drawn radius of every cell.", "> 0"),
            "tiltDegrees" to Pair("How far above the equator the viewer sits. 0 looks straight on.", "finite"),
            "paused" to
                Pair(
                    "Stop the clock and hold the last frame. A new atmosphere set while paused still draws, once, " +
                        "held still.",
                    "",
                ),
            "reducedMotion" to
                Pair(
                    "Hold still: no breath, drift or spin; transitions land at once; a glyph shows at full " +
                        "strength. The platform's reduced-motion setting turns this on too, and a config cannot " +
                        "turn it off.",
                    "",
                ),
            "label" to Pair("What the orb means, for screen readers. Null hides the orb from them.", ""),
            "style" to Pair("The mark each cell is drawn as; see Surface styles.", ""),
            "material" to
                Pair(
                    "What each cell is made of; see Materials. A surface that cannot draw one draws `Material.Flat`.",
                    "",
                ),
        )
}

internal data class ConfigRow(
    val field: Field,
    val meaning: String,
    val constraint: String,
)

internal data class FormRow(
    val name: String,
    val kotlin: String,
    val scale: Float,
    val tipDegrees: Float,
    val meaning: String,
)

/** Whether a look is drawn as itself yet. */
internal enum class Drawn(val label: String) {
    YES("yes"),
    AS_DEFAULT("as `default`"),
    AS_BASE("as BASE"),
}

internal data class LookRow(
    val look: Look,
    val drawn: Drawn,
    val meaning: String?,
)

internal data class AtmosphereRow(
    val name: String,
    val state: AtmosphereState,
    val meaning: String,
)

internal data class StyleRow(
    val style: SurfaceStyle,
    val name: String,
    val meaning: String,
)

internal data class MaterialRow(
    val material: Material,
    val name: String,
    val fields: List<Field>,
    val drawsYet: Boolean,
    val meaning: String,
) {
    /** Where it is drawn, for the docs' tables. */
    val drawn: String
        get() = if (drawsYet) "every target, no shader" else "not yet: draws `Flat`, reports `Unavailable`"
}

/** [value] without a trailing `.0`: `1`, `0.5`, `-35`. */
internal fun num(value: Float): String = value.toString().removeSuffix(".0")

/** [value] as a Kotlin literal: `1f`, `false`, `null`, `"text"`, `SurfaceStyle.Squares`, `Material.Flat`. */
internal fun kotlinLiteral(value: Any?): String =
    when (value) {
        null -> "null"
        is Float -> "${num(value)}f"
        is String -> "\"$value\""
        is SurfaceStyle -> "SurfaceStyle.$value"
        is Material -> "Material.$value"
        else -> value.toString()
    }
