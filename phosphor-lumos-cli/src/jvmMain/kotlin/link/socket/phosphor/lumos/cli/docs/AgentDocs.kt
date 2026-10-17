package link.socket.phosphor.lumos.cli.docs

import java.io.File
import java.util.Locale
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.CognitiveKey
import link.socket.phosphor.lumos.CognitiveLook
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.signal.AtmosphereState

/**
 * The compiled examples the agent docs print, read from `:phosphor-lumos-compose`'s jvmTest,
 * where `ReadmeLumosQuickStartTest` and `AgentDocsExamplesTest` compile each one and draw it. Each
 * is the file as it stands, without its package line.
 */
internal class AgentDocExamples(
    val minimal: String,
    val inline: String,
    val look: String,
    val signal: String,
    val trace: String,
) {
    companion object {
        const val DIRECTORY: String =
            "phosphor-lumos-compose/src/jvmTest/kotlin/link/socket/phosphor/lumos/compose/docs"

        /** The examples under the repository at [root]. */
        fun read(root: File): AgentDocExamples {
            fun example(name: String): String = File(root, "$DIRECTORY/$name.kt").readText().substringAfter("\n").trim()
            return AgentDocExamples(
                minimal = example("ReadmeLumosQuickStart"),
                inline = example("AgentDocsInlineExample"),
                look = example("AgentDocsLookExample"),
                signal = example("AgentDocsSignalExample"),
                trace = example("AgentDocsTraceExample"),
            )
        }
    }
}

/**
 * The consumer-facing Lumos docs: `docs/lumos/llms.txt`, and the `lumos` skill under
 * `skills/lumos/` that `npx skills add socket-link/phosphor --skill lumos` installs.
 *
 * Every section is rendered from [LumosCatalog], so the files cannot drift from the code:
 * `AgentDocsTest` fails when the committed files differ from [render]'s output. Regenerate with
 * `./gradlew :phosphor-lumos:generateAgentDocs`.
 */
internal object AgentDocs {
    const val LLMS_TXT: String = "docs/lumos/llms.txt"
    const val SKILL_DIRECTORY: String = "skills/lumos"

    /** Every generated file, by path from the repository root, in the order they are written. */
    fun render(examples: AgentDocExamples): Map<String, String> =
        linkedMapOf(
            LLMS_TXT to llmsTxt(examples),
            "$SKILL_DIRECTORY/SKILL.md" to skill(examples),
            "$SKILL_DIRECTORY/references/forms.md" to
                reference("Lumos forms", "Where the orb's cells rest, and how to change it.", forms(examples)),
            "$SKILL_DIRECTORY/references/looks.md" to
                reference(
                    "Lumos looks, atmospheres and glyphs",
                    "What the orb shows for an agent's state: how its cells move, what colours them, and the " +
                        "one-shot marks.",
                    looks(examples),
                    phaseLooks(),
                    atmospheres(),
                    glyphs(),
                ),
            "$SKILL_DIRECTORY/references/surface.md" to
                reference(
                    "Lumos on screen",
                    "`LumosOrb`, `LumosConfig`, and the styles and materials a surface draws cells with.",
                    orbParameters(),
                    config(),
                    styles(),
                    materials(),
                ),
            "$SKILL_DIRECTORY/references/sequence.md" to
                reference(
                    "Changing a Lumos orb over time",
                    "Looks, forms, atmospheres and glyphs in motion; an agent's state driving the orb; " +
                        "a recording replayed.",
                    overTime(),
                    signal(examples),
                    trace(examples),
                ),
            "$SKILL_DIRECTORY/catalog.json" to catalogJson(),
        )

    private const val GENERATED =
        "Generated from the Lumos catalogues by `./gradlew :phosphor-lumos:generateAgentDocs`. Do not edit by hand."

    private const val REPOSITORY = "https://github.com/socket-link/phosphor"

    private fun llmsTxt(examples: AgentDocExamples): String =
        document(
            "# Phosphor Lumos",
            SUMMARY,
            ABOUT,
            install(),
            minimal(examples),
            orbParameters(),
            config(),
            forms(examples),
            looks(examples),
            phaseLooks(),
            atmospheres(),
            styles(),
            materials(),
            glyphs(),
            overTime(),
            signal(examples),
            trace(examples),
            doNot(),
            more(),
            "---\n\n$GENERATED",
        )

    private fun skill(examples: AgentDocExamples): String =
        document(
            """
            ---
            name: lumos
            description: >-
              Integrate Phosphor Lumos, the Kotlin Multiplatform voxel orb that shows what an AI agent is
              doing, into an app: LumosOrb for Compose Multiplatform, LumosConfig, forms, looks, atmosphere
              presets, surface styles, materials, glyphs, the phase-to-look table and trace replay. Use when
              adding a Lumos orb, choosing how it looks for an agent's state, or playing a .vxt recording.
            ---
            """.trimIndent(),
            "# Lumos",
            SUMMARY,
            ABOUT,
            install(),
            minimal(examples),
            """
            ## References

            | To | Read |
            |---|---|
            | Pick where the cells rest, size a small orb, or reshape it | [references/forms.md](references/forms.md) |
            | Choose how the orb moves and what colours it for an agent's state, and fire glyphs | [references/looks.md](references/looks.md) |
            | Configure `LumosOrb`: `LumosConfig`, surface styles, materials | [references/surface.md](references/surface.md) |
            | Change the orb over time, drive it from an agent's state, replay a recording | [references/sequence.md](references/sequence.md) |
            | Read every catalogue as data | [catalog.json](catalog.json) |
            """.trimIndent(),
            doNot(),
            "---\n\n$GENERATED",
        )

    private fun reference(
        title: String,
        lede: String,
        vararg sections: String,
    ): String = document("# $title", lede, *sections, "---\n\n$GENERATED")

    private fun document(vararg blocks: String): String = blocks.joinToString("\n\n") { it.trim() } + "\n"

    private val SUMMARY =
        "> Lumos is a voxel orb that shows what an AI agent is doing (thinking, searching, working, waiting " +
            "on a person) as light. It ships as Kotlin Multiplatform libraries: `LumosOrb` for Compose " +
            "Multiplatform, a terminal renderer, and a recorded-trace format for baked clips."

    private val ABOUT =
        """
        An orb is a sphere of cells, from a few dozen to a few thousand. Three independent things decide what
        it shows. The **atmosphere** colours it and sets its pattern and spin (`AtmospherePresets`). The
        **look** decides how its cells move for a cognitive moment (`LumosLook`). The **form** decides where
        its cells rest (`Forms`). A **glyph** is a one-shot mark carved into the cells, such as a check on
        completion. On screen, a **surface style** decides the mark each cell leaves and a **material** how
        that mark takes the light; both are fields of `LumosConfig`.

        `LumosOrb` is the driver. It owns the clock, steps a `LumosFrameSource` once a frame, and draws the
        frame it returns. A source is a live scene (`LiveFrameSource`, or `SignalGenerator` from
        `:phosphor-trace` when an agent's state drives it) or a recording (`LumosSource.Trace`). Classes are
        in `link.socket.phosphor.lumos` unless an import says otherwise.
        """.trimIndent()

    private fun install(): String =
        """
        ## Install

        ```kotlin
        // build.gradle.kts. <version> is the latest release on Maven Central.
        dependencies {
            implementation("link.socket:phosphor-lumos-compose:<version>") // LumosOrb, LumosCanvas
            implementation("link.socket:phosphor-trace:<version>") // SignalGenerator, LumosSource.Trace; optional
        }
        ```

        | Artifact | What it holds |
        |---|---|
        | `phosphor-lumos` | The framework-free orb: `VoxelFrameBuilder`, `LiveFrameSource`, forms, looks, `LumosConfig`. A dependency of the others. |
        | `phosphor-lumos-compose` | `LumosOrb`, the driver, and `LumosCanvas`, which draws one projected frame. |
        | `phosphor-trace` | `SignalGenerator` (an agent's state drives the orb), recordings (`VoxelTrace`, `TraceCodec`) and `LumosSource`. |
        | `phosphor-lumos-cli` | `CliOrb`, the same orb as ANSI in a JVM terminal. |
        """.trimIndent()

    private fun minimal(examples: AgentDocExamples): String =
        section(
            "## A minimal orb",
            kotlinBlock(examples.minimal),
            "`LumosOrb` stops while it is scrolled off screen or its window is in the background, holds still " +
                "when the platform asks for reduced motion, and is one labelled image to a screen reader, or " +
                "hidden from one without a `label`. Orbs on the same look and atmosphere share a clock, so a " +
                "column of them breathes together.",
        )

    private fun orbParameters(): String =
        """
        ## `LumosOrb` parameters

        `link.socket.phosphor.lumos.compose.LumosOrb`

        | Parameter | Type | Default | Meaning |
        |---|---|---|---|
        | `source` | `LumosFrameSource` | required | Where frames come from. `remember` it: a new source restarts the orb. |
        | `config` | `LumosConfig` | `LumosConfig()` | Speed, pause, reduced motion, label, style, material; see `LumosConfig`. |
        | `modifier` | `Modifier` | `Modifier` | Must give the orb an explicit, square size. |
        | `backgroundColor` | `Color` | `Color.Transparent` | An opaque background drawn before the halo and cells. |
        | `showHalo` | `Boolean` | `true` | Paint the ambient breath-ring gradient behind the orb. |
        | `showGlyphOverlay` | `Boolean` | `true` | Overlay the active glyph at the centre when the frame carries one. |
        | `probe` | `FrameProbe` | `FrameProbe.Disabled` | Per-phase timing sink, for benchmarks. |
        """.trimIndent()

    private fun config(): String =
        section(
            "## `LumosConfig`",
            "`LumosConfig` is a serializable data class: how the orb is driven and shown. Its constructor " +
                "throws on a value outside the constraint.",
            table(
                listOf("Field", "Type", "Default", "Constraint", "Meaning"),
                LumosCatalog.config.map { row ->
                    listOf(
                        "`${row.field.name}`",
                        "`${row.field.type}`",
                        "`${kotlinLiteral(row.field.value)}`",
                        row.constraint.ifEmpty { "—" },
                        row.meaning,
                    )
                },
            ),
        )

    private fun forms(examples: AgentDocExamples): String {
        val counts =
            LumosCatalog.latticeCounts.joinToString(", ") { (resolution, count) ->
                "${thousands(count)} at $resolution"
            }
        val inline = Forms.inlineOrbCount(INLINE_PX)
        return section(
            "## Forms",
            "Where the cells rest. The default is `Forms.Lattice(resolution)`, the sphere-shaped integer " +
                "lattice: its count is fixed by the atmosphere's resolution ($counts) and it follows a " +
                "resolution change. `Forms.All` lists the catalogue forms, which take the count they are " +
                "given; `Forms.byName(name)` finds one.",
            table(
                listOf("Name", "Kotlin", "Scale", "Tip", "Meaning"),
                LumosCatalog.forms.map { row ->
                    listOf("`${row.name}`", "`${row.kotlin}`", num(row.scale), "${num(row.tipDegrees)}°", row.meaning)
                },
            ),
            "Scale is how much larger than the unit sphere a framed form is drawn: a solid whose corners touch " +
                "the sphere is drawn larger so its faces reach it too. Tip is the view angle a form asks for; " +
                "pass it as `LumosConfig.tiltDegrees`.",
            "Set a form once, when the source is made: `source.builder.form = Forms.Cube.counted(200)`. " +
                "`counted(n)` pins any form to `n` cells. To reshape a running orb, `morphTo`; see " +
                "Changing the orb over time.",
            "### A small orb",
            "Size the cell count to the orb. `Forms.inlineOrbCount(sizePx, density)` is `sizePx × 4 × density`, " +
                "never fewer than ${Forms.MIN_INLINE_ORB_COUNT}: $inline cells at $INLINE_PX px. A " +
                "resolution-10 lattice at that size is ${thousands(Forms.Lattice(10).count)} cells, most " +
                "of them below the ${num(SurfaceStyle.MIN_RADIUS_PX)} px floor every style draws at.",
            kotlinBlock(examples.inline),
        )
    }

    private fun looks(examples: AgentDocExamples): String =
        section(
            "## Looks",
            "How the cells move for a cognitive moment: Thinking Orbs' catalogue, one look per agent state. " +
                "Wear one with `Look(LumosLook.WORKING)` or a variant with `Look(LumosLook.WORKING, \"gyro\")`; " +
                "the constructor throws on a variant the look does not offer. Every orb on one look shares its " +
                "clock, so they move in step.",
            table(
                listOf("Look", "Variant", "Key", "Period", "Drawn", "Meaning"),
                LumosCatalog.looks.map { row ->
                    listOf(
                        "`${row.look.look.name}`",
                        "`${row.look.variant}`",
                        "`${row.look.key}`",
                        row.look.periodSeconds.let { if (it == 0f) "—" else "${num(it)} s" },
                        row.drawn.label,
                        row.meaning ?: "",
                    )
                },
            ),
            "Period is how often the look's timing repeats: a loop capture of it wants a multiple. *Drawn* " +
                "says what you see today: \"as BASE\" is a look that is declared but not yet ported, and draws " +
                "the resting spin; \"as `default`\" is a variant that draws its look's default motion. Write " +
                "the look you mean anyway: it starts drawing as itself when it is ported.",
            kotlinBlock(examples.look),
        )

    private fun phaseLooks(): String {
        val escalated = choiceText(CognitiveLookSwitch.ESCALATED)
        val unattended = lookText(CognitiveLookSwitch.UNATTENDED)
        return section(
            "## Agent state to look",
            "`CognitiveLookSwitch.DEFAULT` is the table that picks a look from an agent's PROPEL phase " +
                "(`link.socket.phosphor.signal.CognitivePhase`) and activity (`AgentActivityState`). " +
                "`SignalGenerator` installs it; see Driving the orb from an agent's state.",
            table(
                listOf("Phase", "Activity", "Look", "Glyph on arrival"),
                LumosCatalog.switchRows.map { (key, choice) -> switchRow(key, choice) },
            ),
            """
            Precedence, highest first:

            1. Escalated, whatever the table says: $escalated.
            2. Unattended (`LumosRenderConfig.unattended`, an orb nobody is watching): the look becomes $unattended, keeping the row's glyph.
            3. The table, most specific key first: phase and activity, then activity alone, then phase alone, then `${LumosLook.BASE.name}` with no glyph.

            So an activity-only row is a lifecycle override that beats any phase, and a phase-only row holds
            across activities. Pass your own map to `CognitiveLookSwitch(table = ...)` to change a row: a host
            that fires its own CHECK on completion drops that row's glyph.
            """.trimIndent(),
        )
    }

    private fun switchRow(
        key: CognitiveKey,
        choice: CognitiveLook,
    ): List<String> =
        listOf(
            key.phase?.let { "`${it.name}`" } ?: "any",
            key.activity?.let { "`${it.name}`" } ?: "any",
            lookText(choice.look),
            choice.glyph?.let { "`${it.name}`" } ?: "—",
        )

    private fun choiceText(choice: CognitiveLook): String =
        lookText(choice.look) + (choice.glyph?.let { ", with `${it.name}`" } ?: "")

    private fun lookText(look: Look): String =
        if (look.variant == Look.DEFAULT_VARIANT) "`${look.look.name}`" else "`${look.look.name}` · `${look.variant}`"

    private fun atmospheres(): String =
        section(
            "## Atmospheres",
            "What colours the orb and how its colour moves: `AtmosphereState`, in " +
                "`link.socket.phosphor.signal`. `AtmospherePresets` (in `link.socket.phosphor.palette`) holds " +
                "the five a host normally uses, by constant (`AtmospherePresets.THINKING`) or by name " +
                "(`AtmospherePresets.byName(\"thinking\")`). Start a live source on one with " +
                "`LiveFrameSource(AtmospherePresets.THINKING)`.",
            table(
                listOf("Preset", "Use for", "Hues", "Pattern", "Pattern speed", "Spin (y, x)", "Bipolar", "Resolution"),
                LumosCatalog.atmospheres.map { row ->
                    val state = row.state
                    listOf(
                        "`${row.name}`",
                        row.meaning,
                        "${num(state.primaryHue)}° → ${num(state.secondaryHue)}°",
                        "`${state.pattern.name}`",
                        num(state.patternSpeed),
                        "${num(state.rotationY)}, ${num(state.rotationX)}",
                        num(state.bipolarStrength),
                        "${state.resolution} (${thousands(Forms.Lattice(state.resolution).count)} cells)",
                    )
                },
            ),
            "Bipolar above 0 holds the two hues apart as two poles instead of blending them. Every preset's " +
                "other fields are in `catalog.json`. Patterns:",
            table(
                listOf("Pattern", "Meaning"),
                LumosCatalog.patterns.map { (pattern, meaning) -> listOf("`${pattern.name}`", meaning) },
            ),
            "Change the atmosphere with `source.runtime.setAtmospherePreset(\"ready\")` or " +
                "`source.runtime.setAtmosphere(state)`: it blends from wherever it is, over a duration the " +
                "preset pair sets. Pass `AtmosphereTransitionSpec.Immediate` " +
                "(`link.socket.phosphor.choreography`) as `spec` to snap.",
        )

    private fun styles(): String =
        section(
            "## Surface styles",
            "The mark each cell leaves: `LumosConfig.style`, a `SurfaceStyle`. A flat style ignores the " +
                "viewer's tilt. Every style draws a cell at least ${num(SurfaceStyle.MIN_RADIUS_PX)} px across.",
            table(
                listOf("Style", "Flat", "Meaning"),
                LumosCatalog.styles.map { row ->
                    listOf("`SurfaceStyle.${row.name}`", row.style.flat.toString(), row.meaning)
                },
            ),
        )

    private fun materials(): String =
        section(
            "## Materials",
            "What each cell is made of: `LumosConfig.material`, a `Material`. A style decides the mark; a " +
                "material how it takes the light. Any material dresses any style.",
            table(
                listOf("Material", "Fields and defaults", "Drawn", "Meaning"),
                LumosCatalog.materials.map { row ->
                    listOf(
                        "`Material.${row.name}`",
                        row.fields
                            .joinToString(", ") { field ->
                                val constraint = LumosCatalog.materialConstraint(row.name, field.name)
                                "`${field.name}: ${field.type} = ${kotlinLiteral(field.value)}`" +
                                    (constraint?.let { " ($it)" } ?: "")
                            }.ifEmpty { "—" },
                        row.drawn,
                        row.meaning,
                    )
                },
            ),
            "`LumosOrb` falls back silently. To learn whether a material drew, draw the frame with " +
                "`LumosCanvas(frame, onMaterialStatus = ...)`, which reports `MaterialStatus.Ready` or " +
                "`MaterialStatus.Unavailable` once per material.",
        )

    private fun glyphs(): String =
        section(
            "## Glyphs",
            "A one-shot mark carved into the cells and overlaid at the centre, fading in and out over its " +
                "window: `LumosGlyph`. Fire one with `source.builder.queueGlyph(LumosGlyph.CHECK)` (1.5 s " +
                "by default); it replaces any glyph showing. Under the look switch, a row's glyph fires by itself " +
                "when the row is reached.",
            table(
                listOf("Glyph", "Meaning", "Accent (HSL)"),
                LumosCatalog.glyphs.map { (glyph, meaning) -> listOf("`${glyph.name}`", meaning, hsl(glyph)) },
            ),
        )

    private fun hsl(glyph: LumosGlyph): String =
        "${num(glyph.hue)}°, ${percent(glyph.saturation)}, ${percent(glyph.lightness)}"

    private fun percent(fraction: Float): String = "${Math.round(fraction * 100)} %"

    private fun overTime(): String =
        section(
            "## Changing the orb over time",
            "Every change below carries on from where the orb is. Make it on the UI thread, where `LumosOrb` " +
                "steps the source: in a `LaunchedEffect`, a `SideEffect` or a UI callback.",
            table(
                listOf("Change", "Call", "Default"),
                listOf(
                    listOf(
                        "Look",
                        "`source.builder.lookTo(look, durationSeconds)`",
                        "blends over ${num(VoxelFrameBuilder.LOOK_BLEND_SECONDS)} s",
                    ),
                    listOf(
                        "Form",
                        "`source.builder.morphTo(form, count, choreography, durationSeconds)`",
                        "springs over ${num(VoxelFrameBuilder.DEFAULT_MORPH_SECONDS)} s on `${Choreography.FLOW.name}`",
                    ),
                    listOf(
                        "A push",
                        "`source.builder.burst(strength)`",
                        "cells fly out and spring home; Dots' burst is about 6",
                    ),
                    listOf(
                        "Atmosphere",
                        "`source.runtime.setAtmospherePreset(name)`",
                        "blends over the preset pair's duration",
                    ),
                    listOf("Glyph", "`source.builder.queueGlyph(glyph, durationSeconds)`", "shows for 1.5 s"),
                    listOf(
                        "Agent state",
                        "`generator.setPhase(...)`, `setActivity(...)`, `setEscalated(...)`",
                        "picks the look by the table above and blends to it",
                    ),
                ),
            ),
            """
            Setting `source.builder.look` or `source.builder.form` directly is a cut: the next frame wears the
            new look, or rests on the new form, outright. A look asked for mid-blend waits for the blend to land,
            so a signal that flickers changes the look at most once per blend; asking for the look being left
            reverses in place. `morphTo` keeps every cell's identity and springs it to the nearest free place in
            the new form; calling it mid-morph starts a new trip from wherever the cells are.
            """.trimIndent(),
            table(
                listOf("Choreography", "How cells travel in a morph"),
                Choreography.entries.map { listOf("`${it.name}`", choreographyMeaning(it)) },
            ),
        )

    private fun choreographyMeaning(choreography: Choreography): String =
        when (choreography) {
            Choreography.FLOW -> "A gentle arc scaled to the distance: the body reshaping. The default."
            Choreography.SCATTER -> "Every cell flies out before settling: the body bursts and regathers."
            Choreography.DIRECT -> "The straight line each cell's spring gives it."
        }

    private fun signal(examples: AgentDocExamples): String =
        section(
            "## Driving the orb from an agent's state",
            "`SignalGenerator` (`:phosphor-trace`) runs the scene and its frame builder together. Its first " +
                "setter adds an agent to the scene and installs the look switch, so the orb follows the " +
                "agent-state table above and fires its glyphs. `setLook(look)` picks a look by hand.",
            kotlinBlock(examples.signal),
            "The generator is the handle: it keeps its scene and frame builder to itself, so there is no " +
                "`generator.builder`. `setAtmospherePreset(name)`, `setAtmosphere(state)`, " +
                "`morphTo(form, count, choreography, durationSeconds)`, `queueGlyph(glyph)` and `setLook(look)` " +
                "do what the `source.runtime` and `source.builder` calls above do. To give it a small orb's form, " +
                "call `generator.morphTo(Forms.Fibonacci, count = Forms.inlineOrbCount(sizePx = 20), " +
                "durationSeconds = 0f)` once, inside its `remember`: a duration of 0 snaps.",
            "Drive a generator one way at a time. Through `asFrameSource()` the orb steps it; its own " +
                "`frames()` and `nextFrame()` step it at a fixed rate for recording, and using both steps the " +
                "scene twice.",
        )

    private fun trace(examples: AgentDocExamples): String =
        section(
            "## Replaying a recording",
            "A `.vxt` is a recorded orb: `VoxelTrace`, encoded by `TraceCodec` (`:phosphor-trace`). " +
                "`LumosSource.Trace` plays one; `asFrameSource()` hands it to `LumosOrb`. Projection, rotation, " +
                "halo and the glyph overlay stay live over a replay.",
            kotlinBlock(examples.trace),
            "A looping segment's rotation is carried across its seam, so the orb does not drop back each pass; " +
                "pass `carryRotation = false` for the recording untouched. `setSegment` and `seek` are cuts.",
        )

    private fun doNot(): String {
        val lattice = thousands(Forms.Lattice(10).count)
        val floor = num(SurfaceStyle.MIN_RADIUS_PX)
        val speed = "${num(LumosConfig.MIN_SPEED)}..${num(LumosConfig.MAX_SPEED)}"
        val undrawn =
            LumosCatalog.materials.filterNot { it.drawsYet }.joinToString(" or ") { "`Material.${it.name}`" }
        return """
            ## Do not

            - Do not make a source in composition without `remember`. A new source restarts the orb.
            - Do not step one source from two `LumosOrb`s. It runs twice as fast. To show one orb twice, project its frame once and draw it with two `LumosCanvas`es.
            - Do not touch a source, its `builder` or its `runtime` off the UI thread. They are single-threaded and `LumosOrb` steps them on the UI thread, so change them from `LaunchedEffect`, `SideEffect` or a UI callback.
            - Do not leave `LumosOrb` unsized. Give it an explicit, square size.
            - Do not set `builder.look` or `builder.form` to change a running orb. Both cut. Use `lookTo` and `morphTo`.
            - Do not change the cell count or the atmosphere's resolution while a morph is in flight. Either is a cut, and the cells lose their identity.
            - Do not put a small orb on the default lattice. At 20 px a resolution-10 lattice is $lattice cells, most below the $floor px floor. Use `Forms.Fibonacci.counted(Forms.inlineOrbCount(sizePx))`, or on a `SignalGenerator` `generator.morphTo(Forms.Fibonacci, count = Forms.inlineOrbCount(sizePx), durationSeconds = 0f)`.
            - Do not try to turn motion back on under the platform's reduced-motion setting. `reducedMotion = false` cannot overrule it.
            - Do not pass `speed` outside $speed, or `density` or `dotSize` at or below 0. `LumosConfig` throws.
            - Do not expect $undrawn to draw yet. They draw `Material.Flat`.
            - Do not drive a `SignalGenerator` through `asFrameSource()` and `frames()` at once. Its scene steps twice.
            - Do not leave the orb without a `label` when it carries meaning. Without one, screen readers skip it.
            """.trimIndent()
    }

    private fun more(): String =
        """
        ## More

        - The same docs as an installable agent skill: `npx skills add socket-link/phosphor --skill lumos`
        - Source, README and the web (SVG) surface: $REPOSITORY
        - Every catalogue as JSON: $REPOSITORY/blob/main/$SKILL_DIRECTORY/catalog.json
        """.trimIndent()

    private fun catalogJson(): String {
        val catalog =
            linkedMapOf(
                "generatedBy" to GENERATED,
                "config" to
                    LumosCatalog.config.map { row ->
                        linkedMapOf(
                            "name" to row.field.name,
                            "type" to row.field.type,
                            "default" to jsonValue(row.field.value),
                            "constraint" to row.constraint.ifEmpty { null },
                            "meaning" to row.meaning,
                        )
                    },
                "forms" to
                    LumosCatalog.forms.map { row ->
                        linkedMapOf(
                            "name" to row.name,
                            "kotlin" to row.kotlin,
                            "scale" to row.scale,
                            "tipDegrees" to row.tipDegrees,
                            "meaning" to row.meaning,
                        )
                    },
                "latticeCounts" to
                    LumosCatalog.latticeCounts.map { (resolution, count) ->
                        linkedMapOf("resolution" to resolution, "count" to count)
                    },
                "looks" to
                    LumosCatalog.looks.map { row ->
                        linkedMapOf(
                            "look" to row.look.look.name,
                            "variant" to row.look.variant,
                            "key" to row.look.key,
                            "periodSeconds" to row.look.periodSeconds,
                            "drawn" to row.drawn.name.lowercase(),
                            "meaning" to row.meaning,
                        )
                    },
                "agentStateToLook" to
                    LumosCatalog.switchRows.map { (key, choice) ->
                        linkedMapOf(
                            "phase" to key.phase?.name,
                            "activity" to key.activity?.name,
                            "look" to choice.look.look.name,
                            "variant" to choice.look.variant,
                            "glyph" to choice.glyph?.name,
                        )
                    },
                "escalated" to choiceJson(CognitiveLookSwitch.ESCALATED),
                "unattended" to
                    linkedMapOf(
                        "look" to CognitiveLookSwitch.UNATTENDED.look.name,
                        "variant" to CognitiveLookSwitch.UNATTENDED.variant,
                    ),
                "atmospheres" to
                    LumosCatalog.atmospheres.map { row ->
                        val fields = fieldsOf(AtmosphereState.serializer(), row.state)
                        linkedMapOf<String, Any?>("name" to row.name, "meaning" to row.meaning) +
                            fields.associate { it.name to jsonValue(it.value) }
                    },
                "patterns" to
                    LumosCatalog.patterns.map { (pattern, meaning) ->
                        linkedMapOf("name" to pattern.name, "meaning" to meaning)
                    },
                "styles" to
                    LumosCatalog.styles.map { row ->
                        linkedMapOf("name" to row.name, "flat" to row.style.flat, "meaning" to row.meaning)
                    },
                "materials" to
                    LumosCatalog.materials.map { row ->
                        linkedMapOf(
                            "name" to row.name,
                            "fields" to
                                row.fields.map { field ->
                                    linkedMapOf(
                                        "name" to field.name,
                                        "type" to field.type,
                                        "default" to jsonValue(field.value),
                                        "constraint" to LumosCatalog.materialConstraint(row.name, field.name),
                                    )
                                },
                            "drawn" to row.drawn,
                            "meaning" to row.meaning,
                        )
                    },
                "glyphs" to
                    LumosCatalog.glyphs.map { (glyph, meaning) ->
                        linkedMapOf(
                            "name" to glyph.name,
                            "hue" to glyph.hue,
                            "saturation" to glyph.saturation,
                            "lightness" to glyph.lightness,
                            "meaning" to meaning,
                        )
                    },
                "choreographies" to
                    Choreography.entries.map { linkedMapOf("name" to it.name, "meaning" to choreographyMeaning(it)) },
                "timing" to
                    linkedMapOf(
                        "lookBlendSeconds" to VoxelFrameBuilder.LOOK_BLEND_SECONDS,
                        "morphSeconds" to VoxelFrameBuilder.DEFAULT_MORPH_SECONDS,
                    ),
            )
        return toJson(catalog) + "\n"
    }

    private fun choiceJson(choice: CognitiveLook): Map<String, Any?> =
        linkedMapOf("look" to choice.look.look.name, "variant" to choice.look.variant, "glyph" to choice.glyph?.name)

    private fun jsonValue(value: Any?): Any? =
        when (value) {
            is SurfaceStyle, is Material -> kotlinLiteral(value)
            is Enum<*> -> value.name
            else -> value
        }

    private fun section(vararg blocks: String): String = blocks.joinToString("\n\n") { it.trim() }

    private fun kotlinBlock(code: String): String = "```kotlin\n$code\n```"

    private fun table(
        header: List<String>,
        rows: List<List<String>>,
    ): String =
        buildString {
            append(header.joinToString(" | ", "| ", " |")).append('\n')
            append(header.joinToString("|", "|", "|") { "---" })
            for (row in rows) {
                append('\n').append(row.joinToString(" | ", "| ", " |"))
            }
        }

    private fun thousands(count: Int): String = String.format(Locale.ROOT, "%,d", count)

    private const val INLINE_PX: Int = 20
}
