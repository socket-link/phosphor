# SOUL.md

## What This Is

Phosphor is the substance that glows when electrons strike it. In a CRT monitor, phosphor converts invisible energy into visible light. In this library, Phosphor converts invisible cognitive state into visible terminal output.

That's not a marketing metaphor. It's the architecture. Cognitive signals enter one end. Luminous characters exit the other. The library is the phosphor coating between them.

## Why It Exists

AI systems think in the dark. Token probabilities flow, decisions form, actions execute — and none of it is visible until the output arrives. Ampere made cognition *structurally* transparent by emitting events at every phase of the reasoning process. But structured events are data. Phosphor makes them **visible**.

The gap it fills: there is no cross-platform Kotlin library for rendering rich, animated, semantically-meaningful terminal visualizations driven by real-time data. Terminal UIs are either static dashboards or simple progress bars. Phosphor treats the terminal as a display surface for living systems — where motion, color, and density encode meaning.

## How It Thinks About Rendering

A terminal is not a lesser display surface. It is a **different sensory organ** interpreting the same cognitive signal. The same ParticleField that drives a Compose Canvas can drive a character grid. The physics don't change. The rendering surface does.

This insight structures the entire library:

- **Signal** is what the brain is doing. It has no knowledge of pixels, characters, or color.
- **Field** is how that signal behaves in space — particles spawning, attracting, decaying. Still no rendering.
- **Palette** is the retinal cone — it decides what "bright" looks like in character space.
- **Render** is the projection — 3D cognitive space collapsed onto a 2D grid of AsciiCells.
- **Surface Adapter** is the specific sensory organ: Mosaic, Compose Canvas, raw ANSI, or anything else.

Each layer knows only about the layer below it. A change to the palette system cannot break particle physics. A new surface adapter cannot affect signal processing.

## Design Principles

- **Physics over animation.** Don't choreograph keyframes. Define forces, let particles respond. A ParticleField with gravity and attraction produces more organic motion than any hand-tuned sequence.
- **Phase changes are felt, not labeled.** When cognition shifts from PERCEIVE to EXECUTE, the character palette changes from sparse ethereal dots to dense aggressive strokes. Viewers shouldn't need a legend. They should *feel* the shift.
- **Luminance is the primary channel.** Before color, before motion, brightness tells the story. Bright regions are active. Dark regions are quiet. The ASCII character ramp (`. , - ~ : ; = ! * # $ @ █`) is the most information-dense channel in terminal rendering.
- **Depth through projection, not decoration.** The 3D waveform surface uses proper camera→screen projection, not box-drawing tricks. Surface normals determine character selection: `/` for left-facing, `\` for right-facing, `|` for vertical, `-` for horizontal.
- **Transient effects decay naturally.** An EmitterEffect fires and fades. It doesn't need cleanup code. It doesn't linger. Like a spark in the real world: bright, brief, gone.

## Values When Contributing

- **Layer discipline is sacred.** The rendering pipeline has strict unidirectional flow. Violating layer boundaries — even for convenience — creates coupling that makes the system brittle. If you're reaching upward in the pipeline, you're doing it wrong.
- **Pure core, platform edges.** `phosphor-core` must compile without any UI framework dependency. Platform-specific code lives exclusively in surface adapter modules.
- **Visual verification matters.** Math can be correct and the output can still look wrong. When changing anything in palette, projection, or choreography: run the demo and look at it.
- **Performance is a feature.** This library runs in a render loop. Allocations per frame should be minimal. Prefer mutation of existing buffers over creation of new ones in hot paths.

## Working With the Maintainer

The maintainer is the architect; you are the craftsperson.

- **Propose, don't presume.** Rendering is subjective. When a visual choice isn't obvious, show options.
- **Say what you see.** If a character ramp feels wrong, if a color transition is jarring, if particles behave unexpectedly — describe what you observe before suggesting a fix.
- **Treat the pipeline as a circuit.** Understand the current flowing through what you're changing before you change it.
