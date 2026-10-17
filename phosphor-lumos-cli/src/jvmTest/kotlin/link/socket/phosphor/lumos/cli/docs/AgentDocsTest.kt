package link.socket.phosphor.lumos.cli.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.SurfaceStyle

/**
 * The CI check behind PHO-66: the committed `docs/lumos/llms.txt` and `skills/lumos/` are exactly
 * what the generator writes from the catalogues, so a new form, look, style, material, glyph,
 * preset or config field fails here until the docs are regenerated.
 */
class AgentDocsTest {
    private val root = File("..").canonicalFile

    private val rendered: Map<String, String> by lazy { AgentDocs.render(AgentDocExamples.read(root)) }

    @Test
    fun `the committed docs are what the generator writes`() {
        val stale = rendered.filter { (path, text) -> File(root, path).run { !isFile || readText() != text } }.keys
        assertTrue(
            stale.isEmpty(),
            "Out of date: $stale. Run ./gradlew :phosphor-lumos:generateAgentDocs and commit the result.",
        )
    }

    @Test
    fun `the skill folder holds only generated files`() {
        val skill = File(root, AgentDocs.SKILL_DIRECTORY)
        val committed =
            skill
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toSet()
        val extra = committed - rendered.keys

        assertTrue(extra.isEmpty(), "Not generated, so they would drift: $extra")
    }

    @Test
    fun `generating twice writes the same files`() {
        assertEquals(rendered, AgentDocs.render(AgentDocExamples.read(root)))
    }

    @Test
    fun `every surface style the serializer knows is documented`() {
        val documented = LumosCatalog.styles.map { it.name }.toSet()

        assertEquals(sealedSubclasses(SurfaceStyle.serializer().descriptor), documented)
    }

    @Test
    fun `every material the serializer knows is documented`() {
        val documented = LumosCatalog.materials.map { it.name }.toSet()

        assertEquals(sealedSubclasses(Material.serializer().descriptor), documented)
    }

    @Test
    fun `every look and variant has a row`() {
        val rows = LumosCatalog.looks.map { it.look.look to it.look.variant }
        val declared = LumosLook.entries.flatMap { look -> look.variants.map { look to it } }

        assertEquals(declared, rows)
    }

    @Test
    fun `the ported looks draw as themselves and BASE is drawn`() {
        val drawn = LumosCatalog.looks.filter { it.look.variant == "default" }.associate { it.look.look to it.drawn }

        // Ported by PHO-58 and PHO-59; a regression in either would quietly mark them "as BASE".
        val ported =
            listOf(LumosLook.BASE, LumosLook.WORKING, LumosLook.REASONING, LumosLook.SEARCHING, LumosLook.RETRYING)
        for (look in ported) assertEquals(Drawn.YES, drawn[look], "$look")
    }

    @Test
    fun `catalog json parses and carries every section`() {
        val catalog = Json.parseToJsonElement(rendered.getValue("${AgentDocs.SKILL_DIRECTORY}/catalog.json")).jsonObject

        val sections =
            listOf("config", "forms", "looks", "agentStateToLook", "atmospheres", "styles", "materials", "glyphs")
        for (section in sections) assertTrue(section in catalog, "catalog.json has no \"$section\"")
    }

    @Test
    fun `the skill is installable as lumos`() {
        val skill = rendered.getValue("${AgentDocs.SKILL_DIRECTORY}/SKILL.md")

        assertTrue(skill.startsWith("---\nname: lumos\ndescription: "), "SKILL.md must open with name and description")
    }

    @Test
    fun `the docs print the compiled examples`() {
        val examples = AgentDocExamples.read(root)
        val llms = rendered.getValue(AgentDocs.LLMS_TXT)

        for (example in listOf(examples.minimal, examples.inline, examples.look, examples.signal, examples.trace)) {
            assertTrue("```kotlin\n$example\n```" in llms, "llms.txt is missing an example:\n$example")
        }
    }

    // The serial names a sealed serializer knows, by simple name: its "value" element holds one
    // element per subclass.
    @OptIn(ExperimentalSerializationApi::class)
    private fun sealedSubclasses(descriptor: SerialDescriptor): Set<String> =
        descriptor
            .getElementDescriptor(1)
            .elementNames
            .map { it.substringAfterLast('.') }
            .toSet()
}
