package link.socket.phosphor.lumos.cli.docs

import java.io.File

/**
 * Writes the consumer-facing Lumos docs, `docs/lumos/llms.txt` and `skills/lumos/`, from the
 * catalogues (PHO-66). Run it through `./gradlew :phosphor-lumos:generateAgentDocs`, and commit
 * what changes: `AgentDocsTest` fails while the committed files differ from what this writes.
 *
 * @param args The repository root; the working directory when absent.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull()?.takeIf { it.isNotBlank() } ?: ".").canonicalFile
    for ((path, text) in AgentDocs.render(AgentDocExamples.read(root))) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        val changed = !file.exists() || file.readText() != text
        if (changed) file.writeText(text)
        println("${if (changed) "wrote" else "unchanged"} $path")
    }
}
