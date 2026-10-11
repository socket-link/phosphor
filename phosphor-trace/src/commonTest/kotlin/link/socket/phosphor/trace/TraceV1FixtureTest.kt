package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * A v1 `.vxt` payload frozen in source, so "v1 still decodes" is checked against
 * bytes no current encoder produced.
 *
 * The payload was hand-built (CBOR major types written directly, then gzip)
 * rather than captured from `TraceCodec.encode`, so it pins the v1 wire schema
 * itself: if a change to `VoxelTrace`'s serializable shape would misread traces
 * already in the wild, this fails. It exercises a null and a present alpha, a
 * glyph, a defaulted `TraceSegment.loop`, a non-ASCII segment name, uneven cell
 * counts, and a zero scale. Every value is exact in binary32, and several sit
 * off the v2 grids (a position half a step between codes, scales between
 * `1/6561` cells, colours between `k/255` bytes), so a decode that quietly
 * quantized would fail.
 *
 * The generator lives with the PHO-42 recon notes; the hex below is its output.
 */
class TraceV1FixtureTest {
    @Test
    fun `a frozen v1 payload still decodes losslessly`() {
        val trace = TraceCodec.decode(fixtureBytes()).getOrThrow()

        assertEquals(1, trace.formatVersion)
        assertEquals(20, trace.fps)
        assertEquals("0.7.0", trace.phosphorVersion)
        assertEquals(42L, trace.seed)
        assertEquals(mapOf("atmosphere.noise" to "0.2"), trace.paramSnapshot)
        assertEquals(StaticLattice(2), trace.staticLattice)
        assertEquals(
            listOf(
                TraceSegment("idle", startFrame = 0, endFrame = 1, loop = true),
                TraceSegment("idle→thinking", startFrame = 2, endFrame = 2, loop = false),
            ),
            trace.segments,
        )
        assertEquals(1_760_000_000_000L, trace.createdAtEpochMs)
        assertContentEquals(intArrayOf(2, 2, 1), trace.cellCounts)
        assertEquals(EXPECTED_FRAMES, trace.toFrames())
    }

    @Test
    fun `a decoded v1 trace re-encodes as v1 without loss`() {
        val trace = TraceCodec.decode(fixtureBytes()).getOrThrow()

        val again = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        assertEquals(trace, again)
    }

    @Test
    fun `a decoded v1 trace upgrades to v2 within the v2 tolerance`() {
        val v1 = TraceCodec.decode(fixtureBytes()).getOrThrow()
        val upgraded = TraceTestData.withFormatVersion(v1, formatVersion = 2)

        val decoded = TraceCodec.decode(TraceCodec.encode(upgraded)).getOrThrow()

        TraceTestData.assertV2Equivalent(upgraded, decoded)
    }

    private fun fixtureBytes(): ByteArray = V1_FIXTURE_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        val V1_FIXTURE_HEX: String =
            "1f8b08000000000002ffc553bb6e1341149ddd50f001149668a811b2ac34341027b1c2433c8a444249baf1cef5ce38f3" +
                "d2cc58900a2bcbf2011448744480f8032a645cf21dfc03295614dc99b5c316b488628fee9d73ee3d47bbb35fd4c43845" +
                "c333705e189d1513ebaf4d278e2a1899990e1bc672e3f1712b050cfab7fb03e60158efa6b21495079a5acf4d38b734a8" +
                "2806077d6d848762d0df543ed0208ac73420c2f9d4813772167055ce3d940a74f0671f99464726988429ea5db8171310" +
                "0e9aa52a63d218fbf343929928fbf1fa4de0429f085d7626f2cb89dc160e6800b613f6ac29f8137f9d90ecddf75b4b02" +
                "18e4c45759be813114e0b4b2be15092985af7a9b3dd67b3b2d40caf4127c95e719c4f6b06eb6e68434df08c2d62b84c5" +
                "2f84e1fc0a49fc11f2911a6e47fe6aac76a3fc3d69f9e3ba592cf18044d1e22bc29d244710913f28a884ba19da7898e0" +
                "c51ae2481935fbc0ea34d146e954f3d596fb0e40d7ab107fe290d5161e35bb72864677d7739dea32cc8eb49cd6cdcbe5" +
                "da2555c92a56255563815faffa5c96d23cc7588913b14909d2108f6d344b2154ec1eea00da8b709afca471e37d13ef88" +
                "d187295ff7e4282de99e1c27cd3ff6bcf11f3cb7ffe63929e5a9e5bebaf82452f514af368c1eec8d1e71eb4c893f934f" +
                "f678d9db5850b691701b1ba738c85efc06c3b3f77ee7030000"

        fun ambient(rotationY: Float): VoxelAmbient = VoxelAmbient(0.25f, 0.5f, 0.75f, 1f, 0f, rotationY, 0f)

        val EXPECTED_FRAMES: List<VoxelFrame> =
            listOf(
                VoxelFrame(
                    tick = 1L,
                    timestampEpochMillis = 50L,
                    resolution = 2,
                    cells =
                        listOf(
                            VoxelCell(0.25f, 0.125f, -0.375f, 0.9375f, 0.5f, 0.75f, 0.0625f, alpha = null),
                            VoxelCell(-0.5f, 0.75f, 0f, 0.9375f, 0.25f, 0.125f, 1f, alpha = 0.5f),
                        ),
                    ambient = ambient(0.5f),
                    glyph = null,
                ),
                VoxelFrame(
                    tick = 2L,
                    timestampEpochMillis = 100L,
                    resolution = 2,
                    cells =
                        listOf(
                            VoxelCell(
                                0.2578125f,
                                0.1328125f,
                                -0.3671875f,
                                0.96875f,
                                0.5f,
                                0.75f,
                                0.0625f,
                                alpha = null,
                            ),
                            VoxelCell(-0.4921875f, 0.7578125f, 0.0078125f, 0.96875f, 0.25f, 0.125f, 1f, alpha = 0.25f),
                        ),
                    ambient = ambient(0.625f),
                    glyph = VoxelGlyphState("CHECK", progress = 0.5f, red = 0.25f, green = 0.75f, blue = 0.5f),
                ),
                VoxelFrame(
                    tick = 3L,
                    timestampEpochMillis = 150L,
                    resolution = 2,
                    cells =
                        listOf(
                            VoxelCell(1.0001220703125f, -1.25f, 0.5f, 0f, 1f, 0f, 0.5f, alpha = null),
                        ),
                    ambient = ambient(0.75f),
                    glyph = null,
                ),
            )
    }
}
