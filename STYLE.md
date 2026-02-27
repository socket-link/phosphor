# STYLE.md

Style guide for all Phosphor content: documentation, README, code comments, API reference, and demos.

## Voice

**A graphics programmer who paints with semicolons.** Technical precision meets visual intuition. Describes what things look like as naturally as describing what they compute. Understands that a character ramp is simultaneously a data structure and an aesthetic choice. Treats the terminal as a canvas, not a limitation.

## Tone by Context

| Context | Tone | Example |
|---------|------|---------|
| README / hero | Visual, immediate | *"Phosphor turns invisible cognitive state into visible terminal light. The same physics drives every surface — from ANSI escape codes to Compose Canvas."* |
| Technical docs | Precise, spatial | *"AsciiLuminancePalette maps a normalized brightness value (0.0–1.0) to a character. Sparser characters represent dimmer surfaces. Denser characters represent brighter ones."* |
| API reference | Minimal, exact | *"`AsciiCell.fromSurface(luminance, normalX, normalY, palette, colorRamp)` — Constructs a cell from surface properties. The palette determines the character; the ramp determines the color."* |
| Code comments | Grounded in physics | *"// The palette is the retinal cone — it determines what 'brightness' looks like in character space."* |
| Demo output | Descriptive, alive | *"Watch the waveform shift as agents transition from PERCEIVE (cool, sparse) to EXECUTE (dense, bright). The surface isn't decorative — it's a live reading of cognitive load."* |

## Language Principles

**Describe what you see.** Before explaining how code works, describe what it produces. "Sparse floating dots that coalesce into dense bright strokes" teaches more about a phase transition than any class diagram.

**Every sentence earns its place.** If it doesn't teach, clarify, or ground the reader, cut it.

**Physics vocabulary over graphics vocabulary.** Say *luminance* not *brightness*. Say *surface normal* not *direction*. Say *projection* not *mapping*. The precision matters because the library literally implements these concepts.

**Layers are physical.** When describing architecture, use spatial language: signals flow *into* the field, luminance is *projected onto* the cell buffer, surface adapters *transduce* cells into platform output. The pipeline has direction and depth.

## Vocabulary

### Prefer

| Use | Instead of |
|-----|-----------|
| luminance | brightness |
| surface normal | direction / angle |
| ramp | gradient (when describing character/color sequences) |
| cell | character / pixel |
| field | canvas / space |
| transduce | convert / transform (when crossing layer boundaries) |
| decay | fade / disappear |
| fire | trigger / emit (when describing EmitterEffects) |
| phase | state / mode (when describing cognitive states) |
| surface adapter | renderer / backend |

### Avoid

| Word | Why |
|------|-----|
| pixel | Phosphor works in character cells, not pixels. Use *cell.* |
| animate | Too generic. Specify: *spawn, attract, decay, project, pulse.* |
| render (as a noun) | Say *output* or *frame.* Render is a verb. |
| pretty / beautiful | Let the visuals speak. Describe what it looks like, not how it feels. |
| simple / just | Dismissive of the reader's learning curve. |
| framework | Phosphor is a library. It doesn't own your main loop. |
| shader | We have EmitterEffects and palettes. Shader implies GPU pipelines we don't have. |

## The Phosphorescent Model

Phosphor's naming draws from two converging systems: CRT display physics and biological vision.

### Existing Terms

| Term | Function | Physical Basis |
|------|----------|----------------|
| **Phosphor** | The library itself | CRT phosphor coating — converts electron energy to visible light |
| **Luminance** | Normalized brightness (0.0–1.0) | Photometric measure of light intensity per unit area |
| **Palette** | Character selection from luminance | Retinal cone — biological sensor that maps light to perception |
| **Ramp** | Ordered sequence of characters or colors | Phosphor decay curve — brightness falling over ordered time |
| **Cell** | Single character + color in the output buffer | Phosphor dot — the smallest individually addressable light source |
| **Field** | Spatial container for particles and state | Electromagnetic field — forces acting on particles in space |
| **Emitter** | Source of transient visual effects | Electron gun — fires energy that phosphor converts to light |
| **Surface** | The waveform geometry being rasterized | CRT screen — the physical substrate phosphor is deposited on |
| **Choreographer** | Maps cognitive phase to visual behavior | Not physical — the *director* of what the audience sees |

### Naming New Concepts

When naming a new component, ask in order:

1. What does this do in the rendering pipeline? (function)
2. What is the CRT/display physics equivalent? (light and electrons)
3. What is the biological vision equivalent? (eyes and perception)
4. Where do those two converge? (the phosphorescent term)

If both point to the same word, that's the name. If they diverge, prefer the term a graphics programmer would understand without explanation.

## Visual Identity

### Colors (inherited from Ampere, adapted for rendering context)

| Color | Hex | Role |
|-------|-----|------|
| **Phosphor Green** | `#39FF14` | Primary — the classic terminal glow, raw signal made visible |
| **Electric Purple** | `#5639DE` | Secondary — deep processing, the substrate beneath the glow |
| **Signal Amber** | `#FFA736` | Accent — warmth, attention, human-facing moments |

### Cognitive Phase Palette

| Phase | Character Feel | Color Feel |
|-------|---------------|------------|
| PERCEIVE | Sparse, circular, floating (` ·∙.:∘○◌◯`) | Cool blues → white |
| RECALL | Warm, solid, crystallizing (` .·*✦⬡◆●`) | Dark amber → warm gold |
| PLAN | Structured, branching, grid-like (` ░▒▓│├┤┬┴┼`) | Teal → cyan |
| EXECUTE | Dense, aggressive, lightning (` .:;+=*#%@█⚡`) | Red → yellow → white |
| EVALUATE | Diffuse, reflective, fading (` .·:*∘○◌`) | Purple → dim lavender |

### Principles

- **Dark-first.** Light emerges from darkness. The terminal background is the void; phosphor illuminates it.
- **Motion implies life.** Static output is a screenshot. Phosphor output breathes.
- **Density encodes activity.** Dense regions are hot. Sparse regions are cold. The eye reads this instantly.
- **Monospace is the medium.** Every character occupies the same space. This constraint is the art form.
