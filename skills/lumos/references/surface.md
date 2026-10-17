# Lumos on screen

`LumosOrb`, `LumosConfig`, and the styles and materials a surface draws cells with.

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

## `LumosConfig`

`LumosConfig` is a serializable data class: how the orb is driven and shown. Its constructor throws on a value outside the constraint.

| Field | Type | Default | Constraint | Meaning |
|---|---|---|---|---|
| `speed` | `Float` | `1f` | 0.1..3 | Multiplies every step of time the driver hands the source: breath, pattern, spin, transitions, glyph windows. An atmosphere's own rates never change. |
| `density` | `Float` | `1f` | > 0 | Cell-count multiplier for forms whose count is free to vary. The lattice ignores it. |
| `dotSize` | `Float` | `1f` | > 0 | Multiplies the drawn radius of every cell. |
| `tiltDegrees` | `Float` | `0f` | finite | How far above the equator the viewer sits. 0 looks straight on. |
| `paused` | `Boolean` | `false` | — | Stop the clock and hold the last frame. A new atmosphere set while paused still draws, once, held still. |
| `reducedMotion` | `Boolean` | `false` | — | Hold still: no breath, drift or spin; transitions land at once; a glyph shows at full strength. The platform's reduced-motion setting turns this on too, and a config cannot turn it off. |
| `label` | `String?` | `null` | — | What the orb means, for screen readers. Null hides the orb from them. |
| `style` | `SurfaceStyle` | `SurfaceStyle.Squares` | — | The mark each cell is drawn as; see Surface styles. |
| `material` | `Material` | `Material.Flat` | — | What each cell is made of; see Materials. A surface that cannot draw one draws `Material.Flat`. |

## Surface styles

The mark each cell leaves: `LumosConfig.style`, a `SurfaceStyle`. A flat style ignores the viewer's tilt. Every style draws a cell at least 0.45 px across.

| Style | Flat | Meaning |
|---|---|---|
| `SurfaceStyle.Squares` | false | A square per cell, two radii on a side. What every orb drew before styles existed. |
| `SurfaceStyle.Dots` | false | A circle per cell. |
| `SurfaceStyle.Dashes` | false | A round-capped stroke per cell along the spin, 1.8 radii each way at full speed and 0.9 radii wide, so a turning orb reads as motion even when small. |

## Materials

What each cell is made of: `LumosConfig.material`, a `Material`. A style decides the mark; a material how it takes the light. Any material dresses any style.

| Material | Fields and defaults | Drawn | Meaning |
|---|---|---|---|
| `Material.Flat` | — | every target, no shader | The style's own mark. |
| `Material.Ink` | `grain: Float = 0.5f` (0..1), `intensity: Float = 1f` (>= 0) | every target, no shader | Paper grain: each cell's alpha and radius wander by `grain`, drawn as a soft edge under an opaque core. |
| `Material.Neon` | `glow: Float = 0.5f` (0..1), `intensity: Float = 1f` (>= 0) | every target, no shader | An emissive tube: a tinted envelope under a white filament, and the orb's halo widened by `glow`. |
| `Material.Metal` | `roughness: Float = 0.15f`, `lightDegrees: Float = -35f`, `chromatic: Float = 0.35f`, `flow: Float = 1f` | not yet: draws `Flat`, reports `Unavailable` | Brushed metal lit from `lightDegrees`. Declared so the type can be written today. |
| `Material.Glass` | `refraction: Float = 0.65f`, `dispersion: Float = 0.2f`, `frost: Float = 0.03f` | not yet: draws `Flat`, reports `Unavailable` | Refracting glass. Declared so the type can be written today. |

`LumosOrb` falls back silently. To learn whether a material drew, draw the frame with `LumosCanvas(frame, onMaterialStatus = ...)`, which reports `MaterialStatus.Ready` or `MaterialStatus.Unavailable` once per material.

---

Generated from the Lumos catalogues by `./gradlew :phosphor-lumos:generateAgentDocs`. Do not edit by hand.
