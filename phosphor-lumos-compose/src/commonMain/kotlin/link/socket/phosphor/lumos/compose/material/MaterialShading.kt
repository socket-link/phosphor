package link.socket.phosphor.lumos.compose.material

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap

/*
 * PHO-63 spike. Nothing in this package is meant to merge: it exists to answer whether metal and
 * glass can be shaded per cell by a runtime shader on Compose 1.7.0, on every target Socket ships.
 */

/** The two materials under test. T10 (PHO-62) declares them on `Material`; the spike predates it. */
internal enum class MaterialKind { Metal, Glass }

/** What a host would receive through `onMaterialStatus`: whether the shader could be built here. */
internal enum class MaterialStatus { Unknown, Available, Unavailable }

/**
 * One cell's uniforms, mutated in place between cells so the hot path allocates nothing but the
 * platform shader object itself. Pointer defaults to [NO_POINTER], far enough off-canvas that every
 * pointer term in the shaders evaluates to zero.
 */
internal class MaterialCell {
    var centerX: Float = 0f
    var centerY: Float = 0f
    var radius: Float = 1f
    var red: Float = 1f
    var green: Float = 1f
    var blue: Float = 1f
    var alpha: Float = 1f
    var time: Float = 0f
    var lightAngle: Float = MaterialSksl.DEFAULT_LIGHT_ANGLE
    var pointerX: Float = NO_POINTER
    var pointerY: Float = NO_POINTER

    companion object {
        const val NO_POINTER: Float = -1e6f
    }
}

/**
 * A compiled material effect. [brush] shades one disc of [MaterialCell.radius] at its centre;
 * glass also needs [setBackdrop], the image it refracts, in canvas coordinates.
 */
internal interface MaterialShader {
    fun setBackdrop(backdrop: ImageBitmap?)

    fun brush(cell: MaterialCell): Brush

    fun close()
}

/**
 * The platform's runtime-shader implementation of [kind], or null where there is none: Android
 * below API 33, where `android.graphics.RuntimeShader` does not exist. Every Skia-backed target
 * (desktop, iOS, js, wasm) compiles the same SkSL through `org.jetbrains.skia.RuntimeEffect`.
 */
internal expect fun createMaterialShader(kind: MaterialKind): MaterialShader?

/**
 * The shader sources. One text serves both dialects: Skia's SkSL runtime-effect dialect and
 * Android's AGSL are the same language for everything used here (`half4 main(float2)`, `uniform
 * shader` children with `.eval`, user functions, constant-bound loops).
 *
 * Surface geometry is analytic — question 2 of the spike. A cell is a disc of known radius, so its
 * outward normal is `(x, y, √(1 − x² − y²))` in disc-local coordinates and its signed distance is
 * `radius − |p − centre|`; Dots' 192 px normal/SDF map and atlas are not needed for discs or squares.
 */
internal object MaterialSksl {
    const val DEFAULT_LIGHT_ANGLE = -35f
    const val DEFAULT_ROUGHNESS = 0.15f
    const val DEFAULT_CHROMATIC = 0.35f
    const val DEFAULT_REFRACTION = 0.65f
    const val DEFAULT_DISPERSION = 0.2f
    const val DEFAULT_FROST = 0.03f
    const val DEFAULT_INTENSITY = 1f

    private const val COMMON = """
const float PI = 3.14159265;

// A bright bar of the studio's reflection sphere, centred on an angle, soft-edged.
float studioBand(float a, float centre, float halfWidth) {
    float d = abs(mod(a - centre + PI, 2.0 * PI) - PI);
    return 1.0 - smoothstep(halfWidth * 0.5, halfWidth, d);
}
"""

    /**
     * Chrome from drifting studio cards keyed off the reflection vector, a pointer-bent normal,
     * a Blinn-Phong catch light at the light angle, rim light, and a per-channel angular split
     * for the chromatic term. Premultiplied output, analytic edge coverage.
     */
    private const val METAL_SHADE = """
half4 shadeMetal(float2 p, float2 center, float radius, float4 tint, float time,
                 float lightAngle, float2 pointer, float roughness, float chromatic) {
    float2 d = p - center;
    float dist = length(d);
    float sd = radius - dist;
    float cov = clamp(sd + 0.5, 0.0, 1.0);
    if (cov <= 0.0) return half4(0.0);
    float2 q = d / radius;
    float r2 = min(dot(q, q), 1.0);
    float3 n = float3(q, sqrt(1.0 - r2));

    float2 toP = pointer - p;
    float dP = length(toP);
    float reach = radius * 12.0;
    if (dP < reach) {
        n.xy += toP / max(dP, 1.0) * 0.35 * (1.0 - dP / reach);
        n = normalize(n);
    }

    float3 refl = float3(2.0 * n.z * n.x, 2.0 * n.z * n.y, 2.0 * n.z * n.z - 1.0);
    float ang = atan(refl.y, refl.x);
    float la = lightAngle * PI / 180.0;
    float drift = time * 0.4;
    float width = mix(0.35, 0.9, roughness);
    float split = chromatic * 0.18;

    float3 env;
    float aR = ang - split;
    float aG = ang;
    float aB = ang + split;
    env.r = 0.95 * studioBand(aR, la + drift, width)
          + 0.45 * studioBand(aR, la + PI + drift * 0.7, width * 1.8)
          + 0.6 * studioBand(aR, la - 1.9 - drift * 0.5, width * 0.5);
    env.g = 0.95 * studioBand(aG, la + drift, width)
          + 0.45 * studioBand(aG, la + PI + drift * 0.7, width * 1.8)
          + 0.6 * studioBand(aG, la - 1.9 - drift * 0.5, width * 0.5);
    env.b = 0.95 * studioBand(aB, la + drift, width)
          + 0.45 * studioBand(aB, la + PI + drift * 0.7, width * 1.8)
          + 0.6 * studioBand(aB, la - 1.9 - drift * 0.5, width * 0.5);
    // The cards live on the reflection sphere's limb; at the disc's centre the reflection looks
    // straight back at the camera, where the azimuth is undefined, so they fade out there and a
    // flat "camera" reflection takes over. Up (screen -y) is the brighter half of the studio.
    float limb = smoothstep(0.05, 0.6, length(refl.xy));
    float horizon = 0.5 - 0.5 * refl.y;
    env = env * limb + (1.0 - limb) * 0.35 + 0.25 * horizon;

    float3 L = normalize(float3(cos(la), sin(la), 0.9));
    float3 h = normalize(L + float3(0.0, 0.0, 1.0));
    float ndl = max(dot(n, L), 0.0);
    float spec = pow(max(dot(n, h), 0.0), mix(90.0, 12.0, roughness));
    float rim = pow(1.0 - n.z, 3.0);

    float3 base = tint.rgb;
    float3 col = base * (0.35 + 0.4 * ndl)
               + env * mix(1.0, 0.55, roughness) * (0.45 + 0.55 * base)
               + spec * 0.9
               + rim * 0.35 * (0.5 + 0.5 * base);
    float a = cov * tint.a;
    return half4(float4(col * a, a));
}
"""

    /** Metal, one disc per draw: the per-cell path, `drawCircle` under this brush. */
    const val METAL: String = """
uniform float2 center;
uniform float radius;
uniform float4 tint;
uniform float time;
uniform float lightAngle;
uniform float2 pointer;
uniform float roughness;
uniform float chromatic;
""" + COMMON + METAL_SHADE + """
half4 main(float2 p) {
    return shadeMetal(p, center, radius, tint, time, lightAngle, pointer, roughness, chromatic);
}
"""

    /**
     * Metal, every disc in one draw — question 3's batched variant. `idMap` is a canvas-sized
     * image holding, per pixel, 1 + the index of the top-most cell covering it (0 = none), stamped
     * on the CPU in painter's order; `cells` is an N × 3 image of cell data, three bytes per texel
     * with alpha held opaque so sampling never premultiplies the payload:
     *  row 0: cx high, cx low, cy high      (16-bit fixed point, 1/16 px)
     *  row 1: cy low, radius × 4, alpha × 255
     *  row 2: tint r, g, b
     */
    const val METAL_BATCHED: String = """
uniform shader idMap;
uniform shader cells;
uniform float time;
uniform float lightAngle;
uniform float2 pointer;
uniform float roughness;
uniform float chromatic;
""" + COMMON + METAL_SHADE + """
float byteOf(half v) { return floor(float(v) * 255.0 + 0.5); }

half4 main(float2 p) {
    half4 id4 = idMap.eval(p);
    float id = byteOf(id4.r) + byteOf(id4.g) * 256.0;
    if (id < 0.5) return half4(0.0);
    float x = id - 0.5;
    half4 c0 = cells.eval(float2(x, 0.5));
    half4 c1 = cells.eval(float2(x, 1.5));
    half4 c2 = cells.eval(float2(x, 2.5));
    float2 center = float2(byteOf(c0.r) * 256.0 + byteOf(c0.g), byteOf(c0.b) * 256.0 + byteOf(c1.r)) / 16.0;
    float radius = byteOf(c1.g) / 4.0;
    float4 tint = float4(float3(c2.rgb), byteOf(c1.b) / 255.0);
    return shadeMetal(p, center, radius, tint, time, lightAngle, pointer, roughness, chromatic);
}
"""

    /**
     * Glass, one disc per draw. Refracts [backdrop] through a sphere of IOR 1.46 (Snell's law on
     * the analytic normal, travelling the chord), with per-channel dispersion, a five-tap frost
     * blur, Beer-law absorption of the tint's complement along the chord, Schlick Fresnel with a
     * softbox reflection at the light angle, and a pointer catch light.
     */
    const val GLASS: String = """
uniform shader backdrop;
uniform float2 center;
uniform float radius;
uniform float4 tint;
uniform float time;
uniform float lightAngle;
uniform float2 pointer;
uniform float refraction;
uniform float dispersion;
uniform float frost;
uniform float intensity;
""" + COMMON + """
float tap(float2 at, float2 fr, int channel) {
    half4 a = backdrop.eval(at);
    half4 b = backdrop.eval(at + fr);
    half4 c = backdrop.eval(at - fr);
    half4 d = backdrop.eval(at + float2(fr.x, -fr.y));
    half4 e = backdrop.eval(at + float2(-fr.x, fr.y));
    half4 s = (a + b + c + d + e) * 0.2;
    if (channel == 0) return float(s.r);
    if (channel == 1) return float(s.g);
    return float(s.b);
}

half4 main(float2 p) {
    float2 d = p - center;
    float dist = length(d);
    float sd = radius - dist;
    float cov = clamp(sd + 0.5, 0.0, 1.0);
    if (cov <= 0.0) return half4(0.0);
    float2 q = d / radius;
    float r2 = min(dot(q, q), 1.0);
    float nz = sqrt(1.0 - r2);
    float3 n = float3(q, nz);

    float eta = 1.0 / 1.46;
    float k = 1.0 - eta * eta * (1.0 - nz * nz);
    float3 t = float3(0.0, 0.0, -eta) + (eta * nz - sqrt(max(k, 0.0))) * n;
    float thick = 2.0 * nz * radius;
    float2 offs = t.xy / max(-t.z, 0.25) * thick * refraction;
    float2 fr = float2(frost * radius);

    float3 c;
    c.r = tap(p + offs * (1.0 - 0.5 * dispersion), fr, 0);
    c.g = tap(p + offs, fr, 1);
    c.b = tap(p + offs * (1.0 + 0.5 * dispersion), fr, 2);

    c *= exp(-(1.0 - tint.rgb) * (thick / radius) * 1.2 * intensity);

    float F = 0.04 + 0.96 * pow(1.0 - nz, 5.0);
    float la = lightAngle * PI / 180.0;
    float3 L = normalize(float3(cos(la), sin(la), 0.9));
    float3 h = normalize(L + float3(0.0, 0.0, 1.0));
    float ndh = max(dot(n, h), 0.0);
    float soft = smoothstep(0.75, 0.97, ndh);
    float spec = pow(ndh, 80.0);
    float3 refl = float3(2.0 * n.z * n.x, 2.0 * n.z * n.y, 2.0 * n.z * n.z - 1.0);
    float horizon = 0.5 - 0.5 * refl.y;
    c += F * 0.9 * (0.6 + 0.4 * horizon) + soft * 0.3 + spec * 0.7;

    float dP = length(pointer - p);
    c += 0.3 * exp(-dP / (radius * 1.5));

    return half4(float4(c * cov, cov));
}
"""
}
