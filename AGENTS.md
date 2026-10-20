# AGENTS.md

Phosphor is a Kotlin Multiplatform rendering library that translates cognitive state into visible light — ASCII luminance, color ramps, particle physics, 3D waveform surfaces, and the Lumos voxel-orb companion visualization with Compose Canvas support. Read [SOUL.md](SOUL.md) for project philosophy and values.

## Development Commands

### Build & Test
```bash
./gradlew :phosphor-lumos:jvmTest --tests "*OrbStepper*"  # The local pass: one module, narrowed (see Testing)
./gradlew jvmTest                      # Every module's JVM tests (CI's JVM job runs this)
./gradlew iosSimulatorArm64Test        # Run iOS tests (Apple Silicon simulator)
./gradlew jsTest wasmJsTest            # JS and Wasm browser tests (CI's Web job runs this)
./gradlew build                        # Everything; not part of the loop
```

The whole-repo commands are what CI runs on every push. Locally, run one narrow pass at
the end of the work (see "Testing" and "Ticket Delivery Loop").

### Code Quality
```bash
./gradlew ktlintFormat                 # Auto-format code
./gradlew ktlintCheck                  # Check formatting
./gradlew dokkaGeneratePublicationHtml # Generate API documentation
```

### Release
```bash
scripts/release-preflight.sh 0.7.0     # Gate a release, on the commit you intend to tag
scripts/bump-version.sh 0.8.0          # Move main to the next version — AFTER the v0.7.0 tag
./gradlew publishToMavenLocal          # Publish to local Maven for integration testing
```

Publishing is one action — pushing a `v*` tag — and Maven Central is permanent.
Work through [docs/RELEASING.md](docs/RELEASING.md) in order; never skip the
pre-flight, which covers what PR CI does not. `main` always carries the *next*,
unreleased version, so the number to release is the one already in
`gradle.properties` and the tag goes on a commit from before the next bump.
Never bump to release.

Agents never push a tag, and never run `scripts/bump-version.sh` unless the ticket
*is* the bump. Don't run `publishToMavenLocal` unless the ticket asks for it: Socket's
build resolves `mavenLocal()` on this Mac, so a local publish silently changes what
every Socket workspace compiles against. A ticket that needs a release is a reason to
ask (see "When you need Miley").

## Changelog

There is no `CHANGELOG.md`. Do not create one, and do not add a changelog entry
as part of a PR — every parallel PR editing the same "Unreleased" section
guarantees merge conflicts and resets CI on unrelated branches. Release notes
are generated at release time from commit/PR history instead.

## Dependencies and the Yarn Lockfiles

`phosphor-core`, `phosphor-lumos`, `phosphor-lumos-compose` and `phosphor-trace`
have JS and Wasm targets, so the npm packages that Kotlin dependencies bring in
(kotlinx-datetime's `@js-joda/core`, Compose's webpack and Skiko tooling) are
pinned in committed lockfiles under `kotlin-js-store/`: `yarn.lock` for JS and
`wasm/yarn.lock` for Wasm. CI's Web job fails before any test runs when either
one is out of date:

```
Execution failed for task ':kotlinStoreYarnLock'.
> Lock file was changed. Run the `kotlinUpgradeYarnLock` task to actualize lock file
```

That breaks main for every open branch, not only yours. #160 removed
kotlinx-datetime without regenerating the lockfiles, got merged on a red Web job,
and every PR after it inherited the failure.

- If you add, remove, or bump a dependency (any `build.gradle.kts` dependency
  block, a plugin version, or the Kotlin or Compose version), regenerate both
  lockfiles in the same PR and commit whatever changes under `kotlin-js-store/`:
  ```bash
  ./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock
  ./gradlew kotlinStoreYarnLock kotlinWasmStoreYarnLock   # what CI checks
  ```
  These tasks resolve dependencies and run yarn without compiling Kotlin, so
  they finish in seconds.
- If a target ends up with no npm dependencies, the upgrade task **deletes** its
  lockfile. Commit the deletion. A lockfile left with stale entries still fails CI.
- If you didn't touch dependencies, the lockfiles shouldn't change. If they do,
  find out why before committing them.
- A Web job that fails on `kotlinStoreYarnLock` or `kotlinWasmStoreYarnLock` is
  never a flake. Don't merge on it. If main already fails this way, fix it on main
  in its own PR. Don't regenerate the lockfiles on an unrelated branch.
- Don't edit the lockfiles by hand, not even to delete a single entry. Removing
  `@js-joda/core` by hand looked complete, but with it gone Wasm had no npm
  dependencies left. The task deletes the whole file in that case, and the
  remaining `format-util` entry still failed CI.

## Ticket Delivery Loop

Tickets are worked by many agents at once, each in its own Conductor workspace, and Miley is not
watching any one of them. So an agent carries its ticket all the way to a green PR, and speaks up
only when a person is actually needed. If you were started some other way (a plain CLI session)
and your prompt says otherwise, the prompt wins.

**You are finished** when there is a PR against `main` with all four CI checks green
(`JVM, Android, Lint`, `Web (JS, Wasm)`, `Dokka`, `iOS Compile And Test`). Until then, the only
thing that pauses the loop is a question to Miley, asked with Conductor's question tool (see
"When you need Miley"). Ending your turn any other way (CI still running, a red check you haven't
read, a question left in your closing message) strands the ticket, because nobody else is
watching it.

Recon and spike tickets end differently. They have no PR, and their write-up goes on the Linear
ticket as comments, never into the repo (drafting it in `.context/` first is fine). They are
finished when the findings are posted and the ticket's STOP-gate question, if it has one, is
asked. `PHO-*` tickets and their comments sync to the public GitHub repo, so keep anything
private to Socket out of them.

### The loop

1. **Read the ticket.** It lives on Linear as `PHO-<n>`. Its "synced to a corresponding GitHub
   issue" line gives the GitHub number that commits and the PR need. Read the files you will
   touch and their tests (see "Before You Change Anything").
2. **Do the work, with tests, without building.** Builds are the scarce resource when a dozen
   agents share one machine. Check your work by reading code, and build once, at the end.
3. **Validate once, narrowly.** Run `./gradlew :<module>:ktlintFormat` for each module you
   touched, plus the narrowest test task that covers the change (see "Testing"). If you changed a
   dependency, regenerate `kotlin-js-store/` too (see "Dependencies and the Yarn Lockfiles"). CI
   runs every target on every push. The local pass is there to catch the obvious before a
   round trip, not to prove every target. If the machine refuses a local build for lack of
   memory, don't retry and don't work around it. Push, let CI verify, and say so under
   Verification in the PR.
4. **Commit and push** the workspace branch, following "Commit and PR conventions".
5. **Open the PR** with `gh pr create --base main`, ready for review rather than draft. If
   `gh pr create` errors or hangs, run `gh pr list --head "$(git branch --show-current)"` before
   you retry. GitHub sometimes creates the PR and then drops the connection, and a retry opens a
   duplicate.
6. **Wait on CI** with `scripts/pr-checks.sh`. Run it in the foreground (in Claude Code, with the
   Bash tool's `timeout` at its `600000` maximum). It polls quietly, returns within nine minutes,
   and its exit code says what to do next:

   | Exit | Meaning | Next |
   |---|---|---|
   | `0` | Every required check passed, and every other check has finished | Read any red it lists as "not required", then step 8 |
   | `1` | A required check is red, or can no longer run | Step 7 |
   | `2` | No open PR for this branch, or a `gh` error | Step 5, or read the error |
   | `3` | The PR conflicts with `main`, so CI cannot run | Rebase (below), then step 6 |
   | `8` | Still running | Run it again |

   The required set is read from GitHub: today that is the four checks above, from the
   "Protect Main" ruleset. Don't end your turn while the script returns `8`. Spend the wait on
   work that doesn't depend on the result, like the PR body or the visual evidence.
7. **Fix and go again.** Read the failed job's log with
   `gh api repos/{owner}/{repo}/actions/jobs/<id>/logs --allow-escape-sequences` (the script
   prints the exact command). `gh run view --log-failed` refuses until the whole run has finished,
   and the script reports a red as soon as it happens. Decide whether the failure is your diff,
   `main`, or the infrastructure (see "Reading a red check"). Fix it, re-run the narrow local
   check that covers the fix, push, and go back to step 6. A push re-runs every job, so don't
   also re-run jobs by hand.
8. **Finish.** If the work changed along the way, update the PR body to match. If the ticket's
   acceptance is visual, ask for the verdict now (see "Visual evidence"). Then close with a short
   message: the PR link, the script's verdict line, what was verified locally and what only in
   CI, and anything a person still owes. Don't merge; Miley merges. The ruleset also blocks a
   merge while a review thread is unresolved, so answer review comments rather than leaving them.
   If you are asked to merge, use `gh pr merge --squash`, which keeps `main`'s one-commit-per-PR
   history.

**Rebasing.** When `main` moves and the PR conflicts, run
`git fetch origin main && git rebase origin/main`. Use `origin/main`, never the local `main`,
which is shared across workspaces and stale. Resolve the conflicts, and check that
`git diff --check origin/main` reports no leftover conflict markers. If the conflict is in
`kotlin-js-store/`, don't merge the lockfiles by hand: take `origin/main`'s and regenerate. If it
is in `docs/lumos/llms.txt` or `skills/lumos/`, take `origin/main`'s and regenerate with
`./gradlew :phosphor-lumos:generateAgentDocs`. Re-run the narrow local check, then
`git push --force-with-lease`.

**Know when to stop pushing.** If the same required check is still red after three fix attempts,
or it is red and you can't tie it to your diff or to a known infrastructure failure, stop and ask
(see "When you need Miley").

### Visual evidence

Unit tests can't see motion, colour or a seam, so a change to palette, projection, particle,
form, look or surface code comes with evidence a person can judge: a preview the `jvmTest`
harness records (under `build/lumos-previews/` or `build/lumos-surface*/`), a golden render, or a
clip of a demo such as `LookDemo`. Produce it as part of the work and save it under `.context/`.
Don't commit it: binary test assets need approval (see "Safety Boundaries"). Name it in the PR
under Verification, and attach it to the PR or the ticket where you can.

When the ticket's acceptance is visual ("pick dense or fade from the clips", a look that has to
read right), the verdict is a step reserved for a person. Don't decide it yourself, and don't hold
the PR for it. Get the PR green, then ask Miley once, with the PR link and the evidence's path,
and your recommendation first.

### Reading a red check

Not every red is the diff. Before you change code for a failure, check whether `main` shows the
same red (`gh run list --workflow ci.yml --branch main --limit 3`). If it does, the break isn't
yours. Rebase if a newer `main` commit fixes it. Otherwise don't fix it inside your PR: say so in
the PR and ask Miley, who may already have it in hand. Then compare the failure with the table
below. Re-run an infrastructure failure once: `gh run rerun <run-id> --failed`, or
`--job <job-id>` for a single job. GitHub refuses both while any job in the run is still going. A
second identical failure is no longer a flake. Never edit code to make an infrastructure failure
go away, and never re-run a code failure hoping it passes.

Here infrastructure failures are rare. In the 50 failed `ci.yml` runs up to 0.10.0, every red
job was code, the branch's own or inherited from a red `main`. The two flaky signatures were
timeouts in the tests themselves, and both are fixed. So read every red as code until its log
says otherwise. Nine of those PRs were merged on a red check, and each one broke `main` for every
branch after it.

| What you see | Job | What it is | What to do |
|---|---|---|---|
| `Name contains illegal characters: ","` from `compileTestKotlinIosSimulatorArm64` | iOS | Code. A backtick test name Kotlin/Native rejects (see "Testing"); the JVM accepts it. The most common red here | Rename the test. Before pushing, grep new `commonTest` backtick names for `[,.;:/\\<>\[\]]` |
| `Couldn't resolve link for …`, then `❌ Dokka link resolution warnings found:` | Dokka | Code. A KDoc `[link]` to a symbol Dokka can't see (a parameter, a private member, an unimported name) | Fix the link, or make it a code span. Never a flake |
| ``Lock file was changed. Run the `kotlinUpgradeYarnLock` task`` at `:kotlinStoreYarnLock` (or `:kotlinWasmStoreYarnLock`) | Web | Code. `kotlin-js-store/` is out of date | If `main` is green, your dependency change caused it: regenerate and commit the lockfiles (see "Dependencies and the Yarn Lockfiles"). If `main` is red the same way, don't regenerate on your branch; it gets fixed on `main` (#168), then you rebase |
| `KtLint found code style violations`, often `Exceeded max line length (120) (cannot be auto-corrected)` | JVM, Android, Lint | Code | Run `ktlintFormat`, then wrap the lines it can't fix by hand. Run `ktlintCheck` before you push again; it is cheap |
| `e: …` (`Argument type mismatch`, `'val' cannot be reassigned`, …) in every job at once | All four | Code. Usually a signature that changed under a dependent module, often after a rebase | Fix it, then run the narrow test of every module that depends on the one you changed |
| A JS test fails with `AssertionError` or `Expected <…>` on an exact `Float` result, and passes on JVM and Wasm | Web | Code. Kotlin/JS `Float` is a double at runtime | Compare with a tolerance, or compute in `Double` |
| `The bundle is N KB gzipped, over the M KB budget` | Web | Code. `:phosphor-lumos-dom` grew past `checkBundleSize` | Shrink what reaches the bundle. Raising the budget is Miley's call, so ask |
| A browser test `… [js, browser, ChromeHeadless…] FAILED` (or `wasmJs`), followed by a bare `Error` with no assertion text | Web | A mocha timeout, not an assertion. Was a flake in `FormCatalogueTest` and `SignalGeneratorTest`, fixed by `karma.config.d/mocha-timeout.js` | Re-run once if the module has `karma.config.d/mocha-timeout.js` and the test is untouched by your diff. If the module has none, or your diff made the test slower, add the config or speed the test up. `useMocha { timeout }` does nothing on Wasm |
| A job runs far past its timeout and ends `##[error]The operation was canceled.`, the last task line `> Task :phosphor-lumos-compose:jvmTest` (or `:phosphor-lumos-surface:jvmTest`) | JVM, Android, Lint | Code. A Compose test deadlocked (#135) | Drive the scene with `SteppedScene`, not an `ImageComposeScene` waiting on a `delay`. Don't re-run |
| Jobs "fail" within seconds with zero steps (`pr-checks.sh` prints `never started`), every workflow at once, `main` included | Any | Not seen here yet: an Actions billing block, or no runner | Only Miley can fix billing, so ask. Re-run once it is fixed |

To grep a finished job's log, even while the rest of the run is going, use
`gh api repos/{owner}/{repo}/actions/jobs/<id>/logs --allow-escape-sequences | grep -nE 'FAILED|What went wrong|^e: |##\[error\]'`.

### When you need Miley

Ask with Conductor's question tool: `AskUserQuestion` from the Conductor MCP server
(`mcp__conductor__AskUserQuestion` in Claude Code). It notifies Miley and holds your session until
the answer arrives. A question left in your closing message is easy to miss among a dozen
workspaces that all look finished. Without a Conductor tool (a plain CLI session), put the
question first in your closing message and stop.

**Ask when:**
- the ticket is ambiguous or contradicts the code, and the readings lead to materially different
  work;
- the step is destructive, or involves credentials, signing keys, billing, or legal or privacy
  risk;
- something only Miley can change is in the way: Actions billing, the "Protect Main" ruleset,
  repository secrets, the GPG signing key or Maven Central credentials, or a release (pushing a
  `v*` tag, or a version bump the ticket isn't);
- the ticket reserves a step for a person, such as a visual verdict, a STOP gate, or a review it
  names Miley for. If that step doesn't block the PR, ask once the PR is green, so the question
  can carry the PR link;
- the loop is stuck: the same required check is red after three fix attempts, or a red you can't
  attribute.

**Don't ask** for permission to run the loop (committing, pushing, opening the PR, fixing CI,
re-running an infrastructure failure). Don't ask Miley to choose between options when one is
clearly better: pick it, and record the choice and the reason under "Deviations from the ticket"
in the PR. And don't ask whether there's anything else to do.

**How to ask:**
- Make each question stand alone. Miley may be answering from another workspace with none of your
  context. State what you found in a sentence or two, with the `file:line` or the job link, and
  what each answer would lead you to do.
- Offer the options as choices, with your recommendation first and labelled "(Recommended)".
- Put every open decision into one call (the tool takes up to four questions) rather than asking
  them one at a time.
- Before you ask, finish and push everything that doesn't depend on the answer, so the question
  is the only thing left.

### Commit and PR conventions

These apply from the first commit, because a pushed commit can't be fixed without a force-push.
Conductor's own create-PR prompt only fires on its button, so an agent that opens its PR with
`gh` never sees it. These rules are the ones to follow.

- **Titles** carry both ticket IDs, then the task label the ticket gives, if any:
  `PHO-<n>/#<GitHub issue> <task>: <what changed>`, for example
  `PHO-67/#123 T15 · A: Avatar — a face as a GazingForm, …`. Commits and the PR use the same
  shape. A ticket with no synced GitHub issue drops the `/#<n>`.
- **Commit and PR bodies** end with `Authored by Claude (<model>), not by the committer.` (or your
  own agent's name), followed by any attribution trailer your harness adds. Every commit's git
  author is Miley, so the body is the only place that shows who wrote it.
- **The PR body** opens with `Closes #<GitHub issue>` when the PR completes the issue, or
  `Refs #<n>` when it doesn't. Then it covers what was wrong, the change, deviations from the
  ticket and why, and Verification: what ran locally, what was left to CI and why, the visual
  evidence and where it is, and anything a person still owes (a visual verdict, say).
- No changelog entry, in the PR or anywhere else (see "Changelog").

Work without a ticket drops the IDs and keeps the rest.

## Git Worktree Hazards (Conductor)

This repo is commonly checked out as multiple Conductor workspaces, which are
git worktrees sharing a single `.git` directory. Some refs and state are
**not** per-worktree and collide across concurrently running workspaces:

- **Never use `git stash`.** `refs/stash` is a single shared ref across all
  worktrees of the same repo. A concurrent stash from another workspace can
  interleave with yours and mix unrelated changes. Use `git commit` to a
  throwaway/WIP commit (and `git reset --soft HEAD^` to undo) instead of
  stashing.
- Be cautious with other shared, non-worktree-local git state (e.g. global
  config, hooks, `git gc`/`git prune` — these operate on the shared
  `.git` object store) when other workspaces may be active concurrently.
- Conductor names your branch, and may rename it mid-session. That is expected.
  Keep working on whatever branch is checked out, and don't rename it yourself.
- A workspace is cut from `origin/main` once and then drifts as other agents'
  PRs merge. Answer "is X on main?" from `origin/main` (`git fetch origin main`,
  then `git grep <pattern> origin/main`), not from your working tree.
- Act only on your own branch, PR, workflow runs and Actions caches. Other
  workspaces' are someone else's work in progress.

## Architecture at a Glance

Phosphor is a layered rendering pipeline: cognitive signals flow in, visible characters flow out. Each layer depends only on the layer below it — signal code never imports from palette, field code never imports from render.

| Layer | Location | Purpose |
|-------|----------|---------|
| Signal | `signal/` | CognitiveSignal data model — what the brain is doing, no knowledge of pixels or characters |
| Field | `field/` | ParticleField, SubstrateState, FlowLayer — animation physics at a fixed timestep |
| Palette | `palette/` | AsciiLuminancePalette, CognitiveColorRamp — mapping brightness and phase to characters and colors |
| Render | `render/` | AsciiCell, CellBuffer, 3D projection, waveform surface generation |
| Choreography | `choreography/` | CognitiveChoreographer — maps cognitive phases to particle behaviors and visual textures |
| Emitter | `emitter/` | EmitterEffect sealed hierarchy — transient visual events (SparkBurst, HeightPulse, Turbulence, ColorWash) |
| Bridge | `bridge/` | Adapters connecting live runtime state to the animation field |

### Active Modules

| Module | Purpose |
|--------|---------|
| `phosphor-core/` | Shared rendering pipeline — signals, fields, palettes, cells, choreography, emitters, and runtime bridges |
| `phosphor-lumos/` | Framework-free voxel-orb companion visualization for cognitive state, built as a sibling output module over `phosphor-core`; also the `LumosCanvasFrame` DTO, `PixelLattice` projection and `OrbStepper` clock every pixel surface shares |
| `phosphor-lumos-cli/` | JVM terminal frame DTOs, the `CliLattice` projection, and the `CliOrb` ANSI renderer for Lumos voxel frames |
| `phosphor-lumos-compose/` | Compose Multiplatform Canvas rendering surface (`LumosCanvas`, `LumosOrb`) for projected Lumos voxel frames |
| `phosphor-lumos-surface/` | Compose Multiplatform surface (`LumosSurface`, `LumosSurfaceOrb`, `Modifier.lumosTarget`, `LumosSurfaceState`) on which the orb's cells assemble into a layout element and hand off to it; depends on `phosphor-lumos-compose` |
| `phosphor-lumos-dom/` | Kotlin/JS-only DOM/SVG surface (`SvgOrbSurface`, `DomLumosDriver`) shipped as an ES module with a plain-JS `mount(svg, options)`; not published to Maven |
| `phosphor-trace/` | Recorded `VoxelTrace` format, capture, codec, and playback — versioned segments and a CBOR + gzip codec |

### Future / Aspirational Modules

| Module | Purpose |
|--------|---------|
| `phosphor-mosaic/` | Mosaic (Compose-for-terminal) adapter — AnnotatedString output |
| `phosphor-ansi/` | Raw ANSI escape code output for non-Compose terminals |
| `phosphor-demo/` | Desktop demo app (Compose Desktop) |
| `phosphor-demo-cli/` | Terminal demo app |

## Key Paths

| Module | Purpose |
|--------|---------|
| `phosphor-core/src/commonMain/` | Shared rendering pipeline — signals, fields, palettes, cells |
| `phosphor-core/src/commonTest/` | Unit tests for palette mapping, projection math, particle physics |
| `phosphor-lumos/src/commonMain/` | Lumos module boundary for framework-free voxel-orb visualization data |
| `phosphor-lumos/src/commonTest/` | Lumos smoke tests and future voxel-frame API tests |
| `phosphor-lumos-cli/src/commonMain/` | JVM CLI-facing Lumos terminal frame DTOs |
| `phosphor-lumos-cli/src/commonTest/` | CLI DTO serialization and shape-contract tests |
| `phosphor-lumos-compose/src/commonMain/` | Compose Multiplatform Canvas surface for Lumos frames |
| `phosphor-lumos-compose/src/commonTest/` | Compose surface and Compose-state tests |
| `phosphor-lumos-surface/src/commonMain/` | The surface root, the orb's slot, the target modifier, the state that owns the assembly and the hand-off painter |
| `phosphor-lumos-surface/src/commonTest/` | State tests: phases, measured arrival, follow, cancel, release, reduced motion |
| `phosphor-lumos-surface/src/jvmTest/` | Semantics tests under the overlay and the recorded Turn-on moment (`build/lumos-surface/`) |
| `phosphor-lumos-dom/src/jsMain/` | SVG surface, rAF driver, browser facade and the `@JsExport` `mount` API |
| `phosphor-lumos-dom/src/jsTest/` | Golden attribute sets per style and driver gates, against a fake DOM and a fake page (`:phosphor-lumos-dom:jsTest`) |

## Consumer Docs Are Generated

`docs/lumos/llms.txt` and the `skills/lumos/` agent skill (`npx skills add socket-link/phosphor --skill lumos`)
are for agents *integrating* Lumos, not maintaining it. They are generated from the catalogues by
`./gradlew :phosphor-lumos:generateAgentDocs` (the generator is `:phosphor-lumos-cli`'s `docs/` package),
and `AgentDocsTest` in `:phosphor-lumos-cli:jvmTest` fails while the committed files differ from its output.
Never edit them by hand. A new form, look, style, material, glyph, preset or `LumosConfig` field needs its
one-line meaning in `LumosCatalog`, then a regenerate. The examples they print are the compiled files in
`phosphor-lumos-compose/src/jvmTest/.../compose/docs/`.

## Before You Change Anything

- Read the file you're modifying. Read its tests.
- Understand the layer boundary you're in. The pipeline flows strictly downward: Signal → Field → Palette → Render → Surface Adapter.
- If touching particle physics (`field/`), palette or projection code, produce visual evidence — unit tests alone can't catch motion regressions. See "Visual evidence" under "Ticket Delivery Loop".
- If adding a new EmitterEffect, follow the sealed class pattern in `emitter/EmitterEffect.kt`.

## Kotlin Conventions

- Sealed classes/interfaces for closed type hierarchies (EmitterEffect, CognitivePhase, RenderTarget)
- Data classes for immutable value types (AsciiCell, CognitiveColorRamp, Particle)
- `expect`/`actual` for platform-specific rendering only — core math stays in `commonMain`
- Pure functions wherever possible; animation state is the only mutable concern
- ktlint with IntelliJ IDEA code style (auto-format with `./gradlew ktlintFormat`)

## Rendering Pipeline Rules

- Every `AsciiCell` must be constructible from `luminance + normal + palette + colorRamp` — no shortcut paths that bypass the palette system
- Particle physics runs at a fixed timestep (`update(dt: Float)`) independent of framerate
- EmitterEffects are transient — they fire once and decay. Persistent visual states belong in `field/`
- Character ramps must be ordered darkest-to-brightest. Unit tests enforce this invariant
- Color ramps must have at least 5 stops for smooth interpolation
- 3D projection must be configurable (camera position, field of view) — never hardcode perspective
- CellBuffer is the universal interchange format between rendering logic and surface adapters

## Testing

CI is the full gate: its four jobs run every target on every push. Locally, run **one** narrow
pass when the work is done, not after every change. Many agents share a 16 GB Mac, and a
whole-repo `jvmTest` in every workspace after every edit is what stalls it.

**Where tests live.** `commonTest` runs on every target the module has, so a test there is also
run by the Web and iOS jobs. `jvmTest` holds what needs the JVM: the Compose scene and Skia pixel
harnesses in `:phosphor-lumos-compose` and `:phosphor-lumos-surface`, the previews they record
under `build/lumos-previews/` and `build/lumos-surface*/`, and `AgentDocsTest` in
`:phosphor-lumos-cli`. `:phosphor-trace` also has `jsTest`, `webTest` and `iosTest`.

| Module | Narrow local task | Also run in CI by |
|---|---|---|
| `:phosphor-core` | `:phosphor-core:jvmTest` | JVM, Web, iOS; Android compile |
| `:phosphor-lumos` | `:phosphor-lumos:jvmTest` | JVM, Web, iOS; Android compile |
| `:phosphor-lumos-cli` | `:phosphor-lumos-cli:jvmTest` (JVM is its only target) | JVM |
| `:phosphor-lumos-compose` | `:phosphor-lumos-compose:jvmTest` | JVM, Web, iOS; Android compile |
| `:phosphor-lumos-surface` | `:phosphor-lumos-surface:jvmTest` | JVM, Web, iOS; Android compile |
| `:phosphor-lumos-dom` | `:phosphor-lumos-dom:jsTest`, plus `:phosphor-lumos-dom:checkBundleSize` if the bundle can grow | Web |
| `:phosphor-trace` | `:phosphor-trace:jvmTest` | JVM, Web, iOS |

**Narrowing.** Add `--tests "*FooTest*"` when you know which tests cover the change, and
`--offline` when dependencies haven't changed. The traps:

- `:phosphor-lumos-dom` has no JVM target, so `jvmTest` silently skips it. Run its `jsTest`.
- A `--tests` filter that matches nothing in a module fails that module's task. Give each module
  its own filter, in separate invocations if need be.
- A narrow JVM run proves nothing about the other targets. These only fail in CI:
  - backtick test names in `commonTest` must also compile for Kotlin/Native, which rejects some
    characters that the JVM accepts. Leave out `, . ; : / \ < > [ ]` (use `—` to separate
    clauses). Only the iOS job catches this (#165);
  - Kotlin/JS `Float` is a double at runtime, so a test asserting an exact `Float` result can pass
    on JVM and Wasm and fail on JS. Compare with a tolerance;
  - Dokka fails on any `Couldn't resolve link` in KDoc, which no test task checks.
- Compose tests in `jvmTest` drive their scene with `SteppedScene`. An `ImageComposeScene` left
  waiting on a `delay` deadlocked `:phosphor-lumos-compose:jvmTest` for an hour at a time (#135).

**What to test:**

- Test luminance mapping boundaries: `charForLuminance(0.0f)` returns space, `charForLuminance(1.0f)` returns the brightest character
- Test color ramp interpolation at 0.0, 0.5, and 1.0
- Test particle spawn/decay lifecycle to completion
- Test 3D projection against known camera→screen coordinate pairs
- Visual regression tests: render a known CellBuffer to string and compare against golden files in `testdata/`

All four CI jobs (`JVM, Android, Lint`, `Web (JS, Wasm)`, `Dokka`, `iOS Compile And Test`) must
be green before a PR merges into `main`.

## Safety Boundaries

- **Never** add UI framework dependencies to `phosphor-core` — it must remain pure Kotlin
- **Never** hardcode terminal dimensions — always accept width/height parameters
- **Never** assume ANSI color support — surface adapters handle capability detection
- **Never** commit binary test assets without approval
- **Never** create `CHANGELOG.md` or add a changelog entry to a PR — see [Changelog](#changelog)
- **Never** change a dependency without regenerating and committing `kotlin-js-store/` — see [Dependencies and the Yarn Lockfiles](#dependencies-and-the-yarn-lockfiles)
- **Never** merge on a red Web job that failed on `kotlinStoreYarnLock` / `kotlinWasmStoreYarnLock`; it is not a flake
- **Never** push a `v*` tag without `scripts/release-preflight.sh` passing on that exact commit — Maven Central is permanent
- **Never** push a `v*` tag, bump the version unless the ticket is the bump, or run `publishToMavenLocal` unless the ticket asks — see [Release](#release)
- **Never** merge your own PR; Miley merges
- **Always** run one narrow local test pass before pushing, and consider work complete only when every CI check is green — see [Testing](#testing) and [Ticket Delivery Loop](#ticket-delivery-loop)
- **Always** run `./gradlew ktlintFormat` (or `:<module>:ktlintFormat` per module touched) before committing
- **Always** produce visual evidence when changing palette, projection or particle code — see "Visual evidence"

## Permissions

- **File read:** any file in the repository
- **File write:** `src/`, `test/`, `docs/`, config files
- **Git:** branch creation, commits, pushing the workspace branch (`--force-with-lease` after a rebase), and opening and updating that branch's PR
- **Build/test:** `./gradlew` commands, within the one-narrow-pass budget in [Testing](#testing)
- **GitHub:** `gh` for your own branch, PR, workflow runs (read logs, re-run an infrastructure failure) and Actions caches. Not other workspaces' PRs, branch protection, workflow files, secrets or releases
- **Linear:** read the ticket being worked, and comment on it where the ticket asks
- **Library code:** no network requests — this is an offline rendering library. That rule is about what the library does at runtime, not about the agent's tools above
