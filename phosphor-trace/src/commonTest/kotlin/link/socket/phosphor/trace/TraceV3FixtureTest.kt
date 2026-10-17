package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelCell

/**
 * A v3 `.vxt` payload the JVM encoded, frozen in source, so every target — the
 * web's pure-Kotlin inflate above all — decodes bytes zlib wrote rather than
 * bytes its own platform produced.
 *
 * It is a real capture kept small enough to live as text: one second of a
 * resolution-2 orb at 10 fps, idle for five frames and cued to thinking for the
 * last five. zlib wrote it with dynamic Huffman blocks, the inflate path the
 * stored-gzip payloads the web tests build for themselves never reach.
 * Produced on the JVM at `aa811b8` by [TraceCodec.encode] over
 *
 * ```kotlin
 * Oscilloscope.capture(
 *     CaptureConfig(
 *         params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = 2)),
 *         seed = 0x86L,
 *         durationMs = 1_000L,
 *         fps = 10,
 *         segments = listOf(TraceSegment("idle", 0, 4, loop = true), TraceSegment("idle→thinking", 5, 9)),
 *         cues = listOf(AtmosphereCue(atFrame = 5, atmosphere = AtmospherePresets.THINKING.copy(resolution = 2))),
 *         phosphorVersion = "0.9.0",
 *         createdAtEpochMs = 1_760_000_000_000L,
 *     ),
 * )
 * ```
 *
 * The inflated payload's length and CRC are exact on every target. The frame
 * checksums — each frame's cells summed over position, scale and colour — were
 * recorded from the JVM's decode, and other targets hold them within
 * [CHECKSUM_TOLERANCE], since Kotlin/JS does `Float` arithmetic in doubles.
 */
class TraceV3FixtureTest {
    @Test
    fun `the JVM-encoded fixture inflates to the payload zlib compressed`() {
        val payload = gzipDecompress(fixtureBytes())

        assertEquals(PAYLOAD_SIZE, payload.size)
        assertEquals(PAYLOAD_CRC32, PureGzip.crc32(payload))
    }

    @Test
    fun `the JVM-encoded fixture decodes to the recorded frames`() {
        val trace = TraceCodec.decode(fixtureBytes()).getOrThrow()

        assertEquals(3, trace.formatVersion)
        assertEquals(10, trace.fps)
        assertEquals(0x86L, trace.seed)
        assertEquals("0.9.0", trace.phosphorVersion)
        assertEquals(1_760_000_000_000L, trace.createdAtEpochMs)
        assertEquals(
            listOf(TraceSegment("idle", 0, 4, loop = true), TraceSegment("idle→thinking", 5, 9)),
            trace.segments,
        )
        assertContentEquals(IntArray(10) { 81 }, trace.cellCounts)

        val frames = trace.toFrames()
        assertEquals(FRAME_CHECKSUMS.size, frames.size)
        frames.forEachIndexed { index, frame ->
            val checksum = checksum(frame.cells)
            assertTrue(
                abs(checksum - FRAME_CHECKSUMS[index]) <= CHECKSUM_TOLERANCE,
                "frame $index checksum $checksum, recorded ${FRAME_CHECKSUMS[index]}",
            )
        }
    }

    @Test
    fun `a player over the fixture hands back the decoded frames`() {
        val trace = TraceCodec.decode(fixtureBytes()).getOrThrow()
        val frames = trace.toFrames()
        val player = TracePlayer(trace)

        for (index in frames.indices) {
            assertEquals(frames[index], player.frameAt(player.frameDuration * index), "frame $index")
        }
    }

    private fun checksum(cells: List<VoxelCell>): Double =
        cells.sumOf { (it.x + it.y + it.z + it.scale + it.red + it.green + it.blue).toDouble() }

    private fun fixtureBytes(): ByteArray = V3_FIXTURE_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PAYLOAD_SIZE: Int = 11_739
        const val PAYLOAD_CRC32: Int = 965_388_413
        const val CHECKSUM_TOLERANCE: Double = 1e-3

        val FRAME_CHECKSUMS: DoubleArray =
            doubleArrayOf(
                225.55276215076447,
                226.1380655169487,
                226.53521084785461,
                226.90766608715057,
                227.37551367282867,
                227.06332993507385,
                226.44678789377213,
                226.25405287742615,
                225.91132879257202,
                225.4584332704544,
            )

        val V3_FIXTURE_HEX: String =
            "1f8b08000000000000ffed5a097014d7997eafefee39343a4160af13efc655d9b2594120d839ec756320c6602381396c" +
                "6c23a441128c66c4cc0884cb45791d2a8e818d716caf36d900891cc0ce22c7b7d9f8c0f191b5ca8e8d2fec25e5438038" +
                "479a9eb37bfa7afb7a4633d3924680cb64535be55fefff67fabdff7dffff5effefffbb5573a07d7520d8de185ee20d86" +
                "da027eb26975474858b33ad8d8ee9d15e8f4878540476b20843938ace1ad9b72d594bae690d7db3cf127ed1d8d587391" +
                "bfb123d41a081f58dbd4d811ee0c7aa760905553eb82f9cbe6ce606318cf5d106a9e5a5757ec2f9ac1da9d8de176cb92" +
                "170f7404dbda1b831b7fd4e9f54e9b5137a56e836d2ce46d0af89b8747a7ce9c3165c4cc506378d85873dd942b67846d" +
                "43beb696d6b0df1b0a35d54df96ed724dbc8aab68e80af31b8281cf4fa5bc2ad78bc2e68f7a6311cf606fd6df36fba71" +
                "eef58b6fbe6ef686b1838b3af0866093d36674d5da473b7d21efb5ed1dbeb67067b317ef5d5d29853941efba4eafbf69" +
                "23b6fc1dbbcbc14038bb96e51879eaf45223cb2c67d7db37a033b8bab1c92b76b677e0a1a91db6217fa00d6f1ef63164" +
                "eb5c1fe8f2fae63676600b7533ecabdeb8685d6763a815f75f35c3bec3416f28e0ebb46c374e0bd8fa5b7c810d4d53a7" +
                "d4755d8477b1d91bb43a5635fa96e7606e5aef0d06db9abdcdfe4e9fafab7658c5eb6f5ce5f3cef56dec689dd5185cdf" +
                "e66f690e073bbd9dc3c381f6b6b0e8c5b88b9a1a7d96e775ed4d9dde2975531ac373acd0699cd13539df513276a64fc7" +
                "fe5c3c466564085d8943a804cea848ea9a34466364407d63cc7889b0ea9a38d6df5c00ad5eb4f0fa866be797f0d61e61" +
                "186366d72563554a85d9386a8560c3cb9a5e6a59f6a03bab82157b53bb2e1abb73234370fd1885422076d58e191b118f" +
                "25b6cb1e9625ee9a3d3a3bc78ce66314b587ac2534cdc75bdbd6e43db0a6388d40ad216f4bbbd71f0eed3ad0ecc751d6" +
                "dcd6ecf3aec11382b9a803ad383ab3dfa8665f20d0914239bd80a577e4273f0fb7b6f9d7e250b64da10b5378843a9a82" +
                "dec6b0b7f9daf0ec8e4053eb82d06400e02fdebcfc15e0c5ceac0ded820449d10ccbf102c22eb57b314e7b4728a7dce6" +
                "f3b585764d6c9ef8662dbcbc166eaf85c95a62592df1622df98d5a72732d7912ad69f2fa7cd9ac8a15ebc7fe215f4720" +
                "d466ad7551d8dba15e7537005e6bcab2e5cce2a1585c8a4a838343b2a6a46545d1334a3aa328723aada82905020490a1" +
                "63a143884c820450371896a5295e6079272b708240933c2b30e52e8fc7e3aef094a102011b17fa30e53e90f525770972" +
                "97053992febf028261dc1180a034e008d49cdd3180a0c0a53d4425018b76470082918023c187f1b380c80e5898331e20" +
                "b0838c5a724e1320fbeacf0fb0c49683fc04804a01162d21501230ef64def6f05a8b80858102e06797dcf7bde77a0e7c" +
                "e0fab4fa99aeebbfbbb9baea95c33bfb1ffcf7c8daca17fee5a6b01ced617c079372747778e8d2becd333f2df34ffe87" +
                "47a65dfdb393c756f77f32898e39985fffc6943f5bf81f975e762b15fc31955652c7d3c78e1dff3c9200c9781a46350d" +
                "4614c39463a69e4aeb0404101906804027086492142434832a2369bddac978dca8da5d5e45d015ae324aa8fe3b8e9f58" +
                "c3ba33e913f2c0b1935f44e22099484349d3c988629a4adc3464191f60609d650b1012089124848641b848daa872d01e" +
                "9759edf25442fcc54d70951733dcc44acaa52ba7d493c7cf1c8926613a299316603463a24c1c215931b21e9a968706f6" +
                "101114c4c9817411a45e25506e8759e528ab00b4c7e924d9cac91453534e3a4c2d629c3e3934104b12724aa1e29a494b" +
                "1904b404444a06610731a089b7ddc0ce228200d030a113925a054b3839a342707b10461620555e0ba96a37e2a1390486" +
                "4ec74f24d304ce5b4c5c3799b88aa7a54898d14076c918100213a72f0c88bf98504084ea61a083d1ca7967994939050e" +
                "c0b21a03560a06cb10514a8aa44fa7654a55342ea9032ea111144ad1946a90f69362796bed0042bc01321e02f1a4e661" +
                "1c2e8d72f00cd2dd9519b39c5128071b1792836a4495194dd59d49033a531ac5c034c76888b63cb440f17261ee13431b" +
                "9c66a6cb80ce21c54df1ce0cc573a4a1b8ca136a198c830a77aa5c89a22133c319aae149e1a09075966764176f105cf1" +
                "78c0e2379d95f5a45bcb506a0adf5d479ae258a0c51d6591945b3da34faa916b75898941554086599d32e82a55e75dbc" +
                "5cee366857f6a440c2f20d65ef0fbe3150a59319c92da7c964c289182146338c9e392d388f455df1a3996c71598e8b8b" +
                "2cab38cc58924d4a315593332a0404413002c7b3ceb28a783c21a7136a86842649900c2e26e50e67222aa5544556ad8d" +
                "35588ee138a7c795c9c8f828381c3c2a9c5b5bb62e2684426721ede43af3e9128dd42ce4d17c0ff82a80e0ab0322f025" +
                "00910d109cdb435ba22ce43e1b60213d8e011c4e80232acd48407016c0e1ac6e777a38d9db15f3b387336f694094378e" +
                "6c6647f31840700ec091750f8c5efa88cdb6dd91c26d2c1617341a10e51f00eca076df8b80bfbf2db369deb7beadfee5" +
                "be3d65b3c92947665efb0cff6f9e4f05b82cfee2ec5663d3e41d37b65eb97adeb71a363d61fa176a33b7473ebe67d7a2" +
                "aea57def6d7973773f3dddcf2d7b61c9c7b31f7be5cf3ffba8539f3097d49514454141e04f674ec82a8a29103f641284" +
                "e026a91ab74b8e9d89c571b2a4a0499124aaa97672174dce1c9153c4102e14f8b0eb2e9cad3d132670463a8633a88bc5" +
                "b9269d05e4ce68a7640dc433840508059cefab9d0e2531184b240d940524cc9a6a819d549b39a6a489a86200029886db" +
                "838dd754b3488e01c374329ca1a57172e0052ea2476415c633d8439ca1392724aa1cbc9a1a4c2453062221ae54a4595d" +
                "29d0b513b5e3aa4c48192b9903d3e981bcbbba9231e5380614689c7315ec10ce8443e6a0a291895cb601ac13c1729ed1" +
                "956832ad9880b0b20dd42bcb5962e204e3a491a12415594b361d6ec03b2b3d145092b84c7004034d053bc4f04c14c632" +
                "3a95b40a0a9e4e09262a63205263294537b1064e88845e59c69ad555e619a0d171c3cabe18d065b28e0a17849914bec7" +
                "2ca40898c196289e92e884aa73291dd70f5c850941339c94419a7159350149640b4ba6c24da95595c4108d4b9a4958d9" +
                "1cf14e9d113c3c22323206244d8221331882e4c89820ab8623a567634b45bca20950e68824aed280c6b914032a950e22" +
                "515541c504c0c721992d35bc53a57937add1aa6c257815e2a26421f0305ea66a4659cac802ca1a9f9459437273291570" +
                "049b2d2e28592980484505972ca79c718acd6ab2429a64dd20cd6819ebb9206d3a1d9a8990c9837835d28cea94990df7" +
                "649a8f26c8cc892a774a23dd84031f03ac15abe48ca3151e973c912f8b73bc753608968f118c4b8b0b9ab5fb1949cb16" +
                "975bace2423a14c825483a6d70430a499547d3a6e049240cd693349d78085ba0ddf86940704765d25991229824290ca5" +
                "0dd61d930155359846b45bd6298de065c0c99047b68743944f52c527c042822ca603fbb85d141f9c4b03a20b0a08c602" +
                "a21180f6e47efe80e0bc00477958ccc0a30191ad7e14ca5361e228c062262f095878611b0710e59ffacf0a68cbfbb645" +
                "17b2b6fde9227fdb8ab7ab3806f2091d8d325400b495eefcb0cdab5180c5ad2f013862f9b66b3b20b26d536edabacb8e" +
                "86af38ddffc80ee37fbebfc3b14659b1f05feffe2fe71cf9de6f0e3c93b86ad5157bff69cd23d3d74d5bf4e27776670e" +
                "5de67e7cd225edafdefcf7b7f7319b3f5ce9baf1bec8c6f0cb43efd275bf7868fe6d87f6fdb7bc1de954a5063d12c5c4" +
                "4cf7099972d7e084e9e28c8401b8281254e8ea8f52047d7c08422606a8099332a45ba6d84153e71dc7d2c073c91711c0" +
                "5d1cc3d98f28d7a13b0d7983acd061598c62e286d302ac4ae1fcce9ab8a0b092c96bd079244a91d4f128047402903513" +
                "35c2a5506cd43458e1840cca2eee1f02ec4571fc8a437a0ce85608de243d26e18e9374c2749c924957651a220703d2a6" +
                "49c710ab13ce8118ae8627a2d0249290acaec63d1992c28584654f6660d9a4a343889d94c0e9942833014e262c22dd26" +
                "70a5483a690a910ce92c5748e42009c5348804a00c209c48e2c47d4a023ac065ada2dc209cf8d937054d9a3ea30157cd" +
                "80049809495c0a0917824e151717021706a74c5029c40faa84d3add38803b466ea661ae1ec2c9c4e13d038234155cf50" +
                "84c78d7bf01b9742e2ea35a40377e5a938606ad226ae3d4e02083aa44928e072ac12541ab131bc654e9345acc1194855" +
                "65840b00379821801a8982b4a231b04cc0fa267ec364103425045cee3309405762405ce47159c67588861c6d21130ade" +
                "751d3a38d20118c509819c54814e0146d2214a0d46413c815fc45c3403b0031ac121a4c409e0e6879280aa508041039e" +
                "01f8150bf088e6f0175cbe00894bbb93613c804a54322039680099054c1c07af34340822670827128003f034927917d4" +
                "136906b8292905284f06680c2e3380c52fc52e847793a310c0a55e31918370560162b05680d16300c61d889669649c8e" +
                "9e01c78eb21ec429983914f75440e54c4600651816506e15aa3c62ca1083df89daace292fddf332e3013bfa6afe96b3a" +
                "0f5a7fa1e9e7179af65d687afd42d3c7179a8e5f68922e34a52e34c52f349dbcd0d4621598066ff372f21f45714ebedd" +
                "21625e601756cf58c2bd0bac91a2b8c386228ab3f22d3bb4d02ec6055c688d14c51d36141bdd6e71835ddc5e1210f736" +
                "582345515a4fbccde29bede2b6f1f46eb6468aa2b49eb8c2e2a576b1623cbda5d6485194d613ad9d5bb60c8bc5d64d99" +
                "8b37f886f1f4b26a0b16633167ae389e5e96166735678daf90a36516d6ac05e752b3b02cc01f9d536d5916ebdc7aa238" +
                "db023ca783e2b2d9e279e9615a7a3e4a67d3ca3e95cd0d7abd7e7c6c22dd62ae49dde200669bc0bcad7bdbb62c1745f7" +
                "36b15b12310fd804eee98ee45a7487986bd20ef108669bc07cef8e7befcd7251ecb857dc81017748476c02f7ec88e69a" +
                "d43327dfc4cf30db04e67b7aeeb927cb45d1738fd883017ba4cf6ca2479a936fd2de79f9261ec66c1398efda7bd75d59" +
                "2e8abd77897b31e05ee9b04dec95e6e59bd47b63be898730db04e60dbd1b3664b9287a3788bd18b0573a6413bdd28df9" +
                "263d591fd9b3f0d8ef6e1d7a5efc40da2f8a1f4a7b44b1eff8d3a2786abfe8fb4f5fc8e24d9b9edab4e9ce754fadbb73" +
                "ed536bc5e7874471bff48128ee913e14c5a78ff789fb4fd53f292ddc13b9f577c7a46717f7efac7f7ff7ea2f5e99f58e" +
                "f4aa28be1fd9298a2f1d7c6e51fda1d7e6373ddae45ff9e84affe6cdfb376fdee4dbefdbb472ffca59af7c218aaf4aef" +
                "88e2cec8fbf58b9e3bf8d2fcd70e2d7e56aadfd9bf7af7fbd21f967fd2dd706067e0dd3f35f49d7e5b140f0e748bf39e" +
                "7ee1f996a657df5ab9a26745eb929e25ad5bb6bcbc65cbe6352fafd9bcf4e5a50d7f7a5714df3edd374fec1e38d8d4f2" +
                "fc0b4faf7cebd5e57f901aba3f09ec3c201db8e3e0030dbd0f77bed4d7f4c77ebc82773f7f40acdfb7e7d93bc3bdef85" +
                "97fe72e9aa05bf5cb06afbf637b66fdfb6f68db5db16beb1b0a9ef25bc33fd7fac171ff8fcddf09dcfeed9177eaff78e" +
                "0352c303073b1fee955e6fe9db5affab2d5d8ff5addfffd1e179e23b87b75eb764ef434f6cfd71f7073f5d72ff925b66" +
                "df3ffb96071f7cebc1071f0ebc1578f886b76e58dff79838eff047fb975cb7f5f03b3fdefac4437b7ffa4177cbeb52fd" +
                "d6beae2dbf6ab58e8de8ebf4e25353a8b9fdc35c14fde3d6e7f3d23d32cc4571645cc0b3e90ee5db57061c468a4852ae" +
                "1d95725c1447c7052ca93b8c745a92726d40ca71510c8c0b58527718e9945df1a42522e3e28ca031baa7467ce468e8fc" +
                "b04aeb0e239dfe121867a761a4f35ce079d030d29759e5d96918295b6caef575b436fe734b63fbaa36af3fbceb408bf5" +
                "3b22fcd8a65efdc4134fb65917d972a45e7369cf23add6a575ccd46bfc43d176ebea7a7fd8eb0fb58537aad7dc0d802f" +
                "105cd590ff05956afd33cfdeb35cfd8177a568efb925ab83feca4607fe06467f783937edffdee8df627b7f78ff8a6fff" +
                "958d5e7545fddda38cf6561d3abbd1c71ffffdf8460351e99c46bfb76cf717a38cfe39b1eeec46f7edebb51bfde66f7a" +
                "6c463ba4d8398d7e7fc2de85a38c26cbaf3bbbd1471f7d6c7ca3eb62f1731bedb9ab75a4d1ab3dafbd7d76a3bffdedee" +
                "f18d8612c9731afd41555c1865f4f2997f296114ad6eb17e431bdaf5eb0221f4bf7da88a39db2d0000"
    }
}
