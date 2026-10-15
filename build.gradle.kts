plugins {
    kotlin("multiplatform").apply(false)
    kotlin("plugin.serialization").apply(false)
    // Loaded here, beside the Kotlin plugin, so KGP, Dokka and ktlint all see the same AGP classes.
    id("com.android.library").apply(false)
    id("org.jlleitschuh.gradle.ktlint").version("12.2.0").apply(false)
    id("org.jetbrains.dokka").apply(false)
    id("com.vanniktech.maven.publish").apply(false)
}

// "Declared for publishing" and "listed in the release workflow" are two separate facts, and
// they drift apart with no build failure to say so. A module's `mavenPublishing { ... }` block
// declares a coordinate; the `./gradlew` command line in .github/workflows/publish.yml decides
// what a tag actually pushes. :phosphor-trace arrived configured for publishing (#77) and in
// neither list — which would have published :phosphor-lumos-cli with a POM pointing at
// link.socket:phosphor-trace, a coordinate that does not exist on Central, and the release run
// would have gone green. This task fails the build on that drift instead.
//
// Not wired into `check`: it reads workflow files, so it belongs to the release path rather
// than to every compile. scripts/release-preflight.sh and publish.yml both run it by name.
tasks.register("verifyPublishWorkflowCoverage") {
    group = "verification"
    description =
        "Fails if a module configured for Maven Central publishing is missing from the publish " +
            "or dry-run step in .github/workflows/publish.yml."

    val workflowFile = layout.projectDirectory.file(".github/workflows/publish.yml")
    val moduleBuildFiles =
        subprojects
            .map { it.path to it.projectDir.resolve("build.gradle.kts") }
            .filter { (_, buildFile) -> buildFile.exists() }

    inputs.file(workflowFile)
    inputs.files(moduleBuildFiles.map { (_, buildFile) -> buildFile })

    doLast {
        val workflowText = workflowFile.asFile.readText()
        val publishedModules =
            moduleBuildFiles
                .filter { (_, buildFile) -> buildFile.readText().contains("mavenPublishing {") }
                .map { (path, _) -> path }

        if (publishedModules.isEmpty()) {
            throw GradleException(
                "No module declares mavenPublishing { ... }. The module layout moved and this " +
                    "task did not follow it — fix the task rather than deleting it.",
            )
        }

        val missingFromRelease =
            publishedModules.filterNot {
                workflowText.contains("$it:publishAllPublicationsToMavenCentralRepository")
            }
        val missingFromDryRun =
            publishedModules.filterNot { workflowText.contains("$it:publishToMavenLocal") }

        if (missingFromRelease.isNotEmpty() || missingFromDryRun.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "These modules declare Maven Central publishing but are missing from " +
                            ".github/workflows/publish.yml:",
                    )
                    missingFromRelease.forEach { appendLine("  - $it  (Publish to Maven Central step)") }
                    missingFromDryRun.forEach { appendLine("  - $it  (Dry run publish step)") }
                    appendLine()
                    append(
                        "Add them to that step's ./gradlew command. A module missing from the publish " +
                            "step never reaches Central, with a green release run to say it did; one " +
                            "missing from the dry run makes the rehearsal lie about the same thing.",
                    )
                },
            )
        }
    }
}
