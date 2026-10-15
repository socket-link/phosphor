# Releasing Phosphor

A release is one action: **pushing a `v*` tag**. The tag triggers
[`.github/workflows/publish.yml`](../.github/workflows/publish.yml), which
publishes five modules to Maven Central and cuts a GitHub Release.

Maven Central is **permanent**. A published coordinate can never be replaced or
withdrawn, so the tag push is the point of no return — everything before it is
reversible and everything after it is not. That is what step 2 is for.

Work through the steps in order.

| Step | Command | Proceed only if |
|------|---------|-----------------|
| 1. Pick the commit | `git fetch origin && git log --oneline -1 origin/main` | CI is green on it |
| 2. Pre-flight | `scripts/release-preflight.sh <version>` | it prints `PASS` |
| 3. Tag and push | `git tag v<version> <commit> && git push origin v<version>` | — |
| 4. Watch | `gh run watch --exit-status $(gh run list --workflow=publish.yml -L1 --json databaseId -q '.[0].databaseId')` | it succeeds |
| 5. Bump `main` | `scripts/bump-version.sh <next-version>` + PR | — |
| 6. Confirm | see [step 6](#step-6--confirm-the-artifacts-landed) | artifacts are listed |

## How the version moves

**`main` always carries the next, unreleased version.** This is the single most
common source of confusion, so read the timeline rather than guessing:

| When | `phosphorVersion` on `main` | What happened |
|------|-----------------------------|---------------|
| v0.6.2 tagged | `0.6.2` | tag cut from a commit declaring `0.6.2` |
| this convention adopted | `0.7.0` | `main` bumped to the *next* version |
| …commits of feature work… | `0.7.0` | every commit declares the unreleased `0.7.0` |
| v0.7.0 tagged | `0.7.0` | tag cut from one of those commits |
| after that | `0.8.0` | `main` bumped again, to the next unreleased version |

Two consequences, both load-bearing:

- **The version to release is the one already in `gradle.properties`** — you do
  not bump before tagging. If you think you need to bump to release, you are
  about to release the wrong number.
- **The tag must point at a commit from before the next bump landed.**
  `publish.yml` re-reads `gradle.properties` out of the *tagged* tree and
  refuses a mismatch, so a tag placed after the bump fails validation. Tagging
  an older commit is always legal; `v<version>` and `phosphorVersion` simply
  have to agree at that commit.

**The history before `v0.6.2` shows the opposite convention.** Every tag up to
and including `v0.6.2` points at its own `Bump version to X` commit, because
`main` used to carry the version that had just been released. Those commits are
not a template — do not copy their shape.

### Release notes

There is no `CHANGELOG.md`, and there must not be one — see
[AGENTS.md](../AGENTS.md#changelog). Every parallel PR editing the same
`[Unreleased]` section guaranteed a merge conflict. `publish.yml` creates the
GitHub Release with notes generated from the PR history since the previous tag,
so **a PR's title and description are its release note**: write them for someone
upgrading, and say when observable output changes.

The hand-written changelog through `0.8.0` is still in history:
`git show a12ba0b:CHANGELOG.md`.

## Step 1 — pick the commit

```bash
git fetch origin
git log --oneline -1 origin/main
gh run list --branch main -L 3
```

Releases are cut from `main`. Confirm CI is green **on that exact SHA**, then
check it out so the pre-flight validates the tree you are about to tag:

```bash
git checkout <commit>
```

## Step 2 — pre-flight

```bash
scripts/release-preflight.sh 0.7.0
```

Pass the version you intend to release — the one already in `gradle.properties`.
The script refuses to go further unless that matches, the tree is clean, no such tag exists, the commit is contained in `origin/main`, and every
publishing module appears in both workflows' Apple-target lists. Then it runs
one Gradle build.

**Do not skip this and lean on PR CI.** CI covers this repository's *source*
surface well — `jvmTest`, `jsTest`, `wasmJsTest`, `ktlintCheck`,
`dokkaGeneratePublicationHtml` and `iosSimulatorArm64Test` all run on every pull
request, and no module declares a target-specific Apple source set, so the
simulator job compiles the same code the device and x64 targets do. What CI does
not cover is everything about a release that is not source, and a tag cannot
take those back:

- **A module can declare `mavenPublishing { }` and be absent from
  `publish.yml`'s command line.** Nothing fails when those two drift.
  `:phosphor-trace` arrived that way (#77): `:phosphor-lumos-cli` depends on it,
  so the release would have pushed a POM naming `link.socket:phosphor-trace`, a
  coordinate that did not exist on Central — and the run would have gone green.
  `verifyPublishWorkflowCoverage` (root `build.gradle.kts`) now fails the build
  on that drift, and the pre-flight additionally refuses a publishing module
  whose Apple device target neither workflow compiles.
- **`iosX64` is produced by no job before the tag**, and every publication
  includes it. Sharing its sources with the simulator target makes a *source*
  failure unlikely; what it does not rule out is the toolchain — and a native
  target that fails to build during the publish step fails it partway, with
  earlier modules already on Central. One compile per release is a cheap premium
  against a burned version.

What the pre-flight deliberately leaves out, so the single build stays
affordable on a 16GB machine: `jsTest` and `wasmJsTest` (on every PR, and the
reason this repository has no "JVM-only API in `commonMain`" hole — every
module's `commonMain` is compiled for at least one target with no JVM stdlib on
every pull request),
`iosSimulatorArm64Test` (on every PR, across every module), and
`dokkaGeneratePublicationHtml` (on every PR with the same link grep, and
`publish.yml` runs it *before* the publish step, so a failure there costs a tag
rather than a version).

If the pre-flight fails: fix it on a normal PR, merge, and start again from step
1 on the new `main`. Do not tag a tree you had to patch locally.

## Step 3 — tag and push

```bash
git tag v0.7.0 <commit>
git push origin v0.7.0
```

Lightweight tags, matching every release so far. Pushing the tag is the release.

## Step 4 — watch the publish

```bash
gh run watch --exit-status $(gh run list --workflow=publish.yml -L1 --json databaseId -q '.[0].databaseId')
```

On a macOS runner. In order, the job validates the tag against
`gradle.properties`, runs `verifyPublishWorkflowCoverage` with every module's
`jvmTest`, iOS device compile and Android compile, checks Dokka for unresolved
links, decodes the signing key, publishes all five modules, then creates the
GitHub Release with generated notes.

A failure *before* the publish step means nothing was published: fix forward,
delete the tag (`git push origin :refs/tags/v0.7.0`), and start again. A failure
*during* the publish step may have published some modules already — treat the
version as burned and release the next patch instead.

## Step 5 — bump `main` for the next cycle

```bash
git checkout -b bump-version-0.8.0 origin/main
scripts/bump-version.sh 0.8.0
git commit -am "Bump version to 0.8.0"
gh pr create --base main --title "Bump version to 0.8.0"
```

The argument is the **next** version. The one just released is read from
`gradle.properties`, and the script refuses to run unless its tag already
exists — that guard is what keeps a bump from landing before a release.

Minor bump for a normal release; patch only for a release that is itself a fix
on top of a published version. A new module is a minor. Phosphor is pre-1.0, so
breaking changes go in a minor.

Nothing else belongs in this PR — no release notes, and
[no changelog entry](../AGENTS.md#changelog): release notes are generated from
history at tag time, and a `CHANGELOG.md` would conflict across every parallel
PR. The PR runs the full CI matrix, iOS job included, so expect it to take as
long as a feature PR.

## Step 6 — confirm the artifacts landed

Central takes up to ~30 minutes to index.

- <https://central.sonatype.com/publishing> — publishing status
- <https://repo1.maven.org/maven2/link/socket/> — the artifacts themselves
- `gh release view v0.7.0`

Check all five coordinates, not one. A green run is not evidence that every
module reached Central; that is the failure `verifyPublishWorkflowCoverage`
exists to prevent, and it only covers the drift it can read off the workflow.

## What gets published

Five modules, all at `phosphorVersion`, under the `link.socket` group:

| Module | Coordinate |
|--------|------------|
| `phosphor-core` | `link.socket:phosphor-core` |
| `phosphor-lumos` | `link.socket:phosphor-lumos` |
| `phosphor-lumos-cli` | `link.socket:phosphor-lumos-cli` |
| `phosphor-lumos-compose` | `link.socket:phosphor-lumos-compose` |
| `phosphor-trace` | `link.socket:phosphor-trace` |

The list lives in `publish.yml`'s run commands — the publish step and the
dry-run step, both of which `verifyPublishWorkflowCoverage` reads. The modules
depend on each other (`:phosphor-lumos-cli` → `:phosphor-trace` →
`:phosphor-lumos` → `:phosphor-core`), so an omission is not one missing
artifact but an unresolvable POM in whatever depended on it.

## Rules

- **Never force-move or delete a tag whose publish step has started.** Central
  artifacts outlive the tag; a moved tag makes the two disagree forever.
- **Never leave a `-SNAPSHOT` suffix on `main`.** `publish.yml` compares the tag
  to `phosphorVersion` as an exact string.
- **Never bump to release.** `main` carries the next unreleased version, so the
  number to tag is already in `gradle.properties`. A bump before the tag moves
  `main` off that number and `publish.yml` then refuses the tag.
- **Never hand-edit `phosphorVersion`.** Use `scripts/bump-version.sh`, which
  refuses to bump an untagged version and reads the result back.
- **Never add a `CHANGELOG.md`.** Release notes are generated from PR history;
  see [AGENTS.md](../AGENTS.md#changelog).
- **Never tag a commit that is not on `main`.** The pre-flight checks this; the
  tag outlives the branch.
- **Never run the pre-flight as several `./gradlew` calls.** On a constrained
  machine the build admission hook allows one build at a time and refuses the
  rest; add tasks to the script's list instead.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Tag version (X) does not match gradle.properties (Y)` | tag placed after the next-cycle bump, or on the wrong branch | delete the tag, re-tag a commit where `phosphorVersion` is X |
| pre-flight: `you asked to release X but gradle.properties declares Y` | releasing the wrong number — Y is the unreleased one | release Y, or check out a commit from before the bump to X landed |
| `bump-version: vX was never tagged` | bumping before the release | tag vX first; the bump is step 5, not step 2 |
| pre-flight: `HEAD is not contained in origin/main` | releasing from a branch, or a stale remote ref | `git fetch origin`, then check out the main commit |
| publish fails in `compileKotlinIosX64` | an Apple target no CI job compiles | fix on a PR; the pre-flight would have caught it |
| publish fails in a module CI never compiles for `iosArm64` | workflow list drift | add the module to both workflows' lists |
| `Couldn't resolve link` in the Dokka step | a KDoc reference that does not resolve | fix the KDoc; nothing was published, the tag is reusable after deletion |
| A module is missing from Central but the run was green | publish-list drift | `./gradlew verifyPublishWorkflowCoverage` |
| `Signature verification failed` | GPG public key not on the keyserver | `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>` |
| Nothing on Central after 30 min | Portal lag or a validation hold | check <https://central.sonatype.com/publishing> |
| POM validation failed | missing POM metadata | `./gradlew :phosphor-core:generatePomFileForKotlinMultiplatformPublication` and read `phosphor-core/build/publications/kotlinMultiplatform/pom-default.xml` |

## Appendix A — one-time setup

Needed once per machine or per repository, not per release.

### Maven Central Portal account

1. Create an account at <https://central.sonatype.com>.
2. Register the `link.socket` namespace.
3. Generate a user token at <https://central.sonatype.com/account>.

### GPG key

```bash
# RSA 4096, no expiration for release keys
gpg --full-generate-key

# The key ID is the last 8 characters of the long ID
gpg --list-secret-keys --keyid-format LONG

# Maven Central requires the public key to be discoverable
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### Local credentials

In `~/.gradle/gradle.properties` — never in the repository, and never in
`local.properties`:

```properties
mavenCentralUsername=your-token-username
mavenCentralPassword=your-token-password
signingInMemoryKeyId=ABCD1234
signingInMemoryKey=exported-ascii-armored-key
signingInMemoryKeyPassword=your-gpg-passphrase
```

Each module disables signing when the task name ends in `publishToMavenLocal`,
so a local integration-test publish needs no key at all.

### GitHub secrets

| Secret | Value |
|--------|-------|
| `MAVEN_CENTRAL_USERNAME` | Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Portal token password |
| `SIGNING_KEY_ID` | last 8 characters of the GPG key ID |
| `SIGNING_KEY` | `gpg --armor --export-secret-keys YOUR_KEY_ID \| base64` |
| `SIGNING_PASSWORD` | GPG passphrase |

## Appendix B — rehearsals and manual publishing

**Dry run on CI.** `gh workflow run publish.yml -f dry_run=true` runs the full
pre-publish gate on a macOS runner and then publishes every module to that
runner's Maven Local. Unlike a Gradle `--dry-run`, this compiles every target
and generates every POM and javadoc jar for real — it proves the publish wiring
resolves and the artifacts build. It does not sign (signing is disabled for
`publishToMavenLocal`) and Central never sees it, so it is a complement to
`release-preflight.sh` rather than a substitute.

**Local integration testing.** `./gradlew publishToMavenLocal` puts all five
modules in `~/.m2` at the current `phosphorVersion` — the path for trying a
change against a consumer before it is released.

**Publishing by hand.** Only when the workflow itself is broken and the tag is
already pushed. Publish every module, or the release is partial:

```bash
./gradlew \
  :phosphor-core:publishAllPublicationsToMavenCentralRepository \
  :phosphor-lumos:publishAllPublicationsToMavenCentralRepository \
  :phosphor-lumos-cli:publishAllPublicationsToMavenCentralRepository \
  :phosphor-lumos-compose:publishAllPublicationsToMavenCentralRepository \
  :phosphor-trace:publishAllPublicationsToMavenCentralRepository \
  -PmavenCentralUsername=YOUR_TOKEN_USERNAME \
  -PmavenCentralPassword=YOUR_TOKEN_PASSWORD \
  -PsigningInMemoryKeyId=KEY_ID \
  -PsigningInMemoryKey="$(gpg --armor --export-secret-keys KEY_ID)" \
  -PsigningInMemoryKeyPassword=KEY_PASSPHRASE
```

This is a full multiplatform publish from one machine — JVM, Android, JS, Wasm
and every Apple target. It needs macOS, an Android SDK, and a lot of memory.

## Version numbering

[Semantic Versioning](https://semver.org/), pre-1.0:

- **MAJOR** — reserved; Phosphor is on `0.x`.
- **MINOR** — features, a new module, and breaking API changes while pre-1.0.
- **PATCH** — fixes on top of a published version.

A change to the rendering pipeline's observable output
— a character ramp reordered, a projection convention moved — is a breaking
change even when the API signature holds, because the consumer's output changes.
Say so in the PR title and description — they are what the generated release
notes are built from.
