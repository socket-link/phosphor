#!/bin/sh
#
# release-preflight.sh
#
# The gate a release must pass before its tag is pushed. Run it on the exact
# commit you intend to tag, with the version you intend to release:
#
#   scripts/release-preflight.sh 0.7.0
#
# Exit 0 means this tree is safe to tag. Any other exit means do not tag.
#
# Why this exists: pushing a `v*` tag publishes every module to Maven Central,
# and Central is permanent — a coordinate that lands there can never be replaced
# or withdrawn. The tag push is the point of no return, so everything checkable
# gets checked on this side of it.
#
# PR CI covers phosphor's source surface well: jvmTest, jsTest, wasmJsTest,
# ktlintCheck, dokkaGeneratePublicationHtml and the iOS simulator tests all run
# on every pull request, and since no module declares a target-specific Apple
# source set, the simulator job compiles the same code the device and x64
# targets do. What it does not cover is everything about a release that is not
# source:
#
#   1. A module can declare `mavenPublishing { }` and still be absent from the
#      publish workflow's command line. Nothing fails when those two drift.
#   2. `iosX64` is produced by no job before the tag, and every publication
#      includes it. A source failure there is unlikely; a toolchain one is not
#      ruled out, and it would fail the publish step partway through.
#
# Deliberately ONE `./gradlew` invocation. On a memory-constrained machine the
# build admission hook allows a single build at a time and refuses the rest, so
# splitting these tasks across calls gets the later ones denied. Add tasks to
# the list, not calls.

set -eu

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$REPO_ROOT" ]; then
    printf 'release-preflight: not inside a git repository.\n' >&2
    exit 1
fi
cd "$REPO_ROOT"

EXPECTED_VERSION=${1:-}
if [ -z "$EXPECTED_VERSION" ]; then
    printf 'release-preflight: usage: scripts/release-preflight.sh <version>   (e.g. 0.7.0)\n' >&2
    exit 1
fi

# --- 1. gradle.properties must already declare the version you mean to release.
#
# `main` carries the NEXT, unreleased version, so the number to release is the
# one already in the tree — you do not bump to release. publish.yml re-reads
# gradle.properties out of the tagged tree and refuses a mismatch.
GRADLE_VERSION=$(sed -n 's/^phosphorVersion=//p' gradle.properties)

if [ "$GRADLE_VERSION" != "$EXPECTED_VERSION" ]; then
    printf 'release-preflight: you asked to release %s but gradle.properties declares phosphorVersion=%s.\n' \
        "$EXPECTED_VERSION" "$GRADLE_VERSION" >&2
    printf '  main carries the NEXT unreleased version, so tag a commit from BEFORE the next bump\n' >&2
    printf '  landed, not after it. If you think you need to bump to release, you are about to\n' >&2
    printf '  release the wrong number.\n' >&2
    printf '  See docs/RELEASING.md, "How the version moves".\n' >&2
    exit 1
fi

# --- 2. The tree must be clean, or you are not validating what you will tag.
if [ -n "$(git status --porcelain)" ]; then
    printf 'release-preflight: working tree is dirty. Commit or discard first — a tag points at a\n' >&2
    printf '  commit, so anything uncommitted is validated here and then not published.\n' >&2
    exit 1
fi

# --- 3. The tag must not already exist. A published tag is never force-moved.
if git rev-parse -q --verify "refs/tags/v$EXPECTED_VERSION" >/dev/null; then
    printf 'release-preflight: tag v%s already exists. Releases are immutable; pick the next version.\n' \
        "$EXPECTED_VERSION" >&2
    exit 1
fi

# --- 4. The commit must be on main.
#
# Releases are cut from main. Tagging a branch commit publishes code that was
# never reviewed, and the tag then outlives the branch.
if git rev-parse -q --verify refs/remotes/origin/main >/dev/null; then
    if ! git merge-base --is-ancestor HEAD origin/main; then
        printf 'release-preflight: HEAD is not contained in origin/main.\n' >&2
        printf '  Releases are cut from main. `git fetch origin` if this is stale, otherwise check\n' >&2
        printf '  out the main commit you mean to tag.\n' >&2
        exit 1
    fi
else
    printf 'release-preflight: note — no origin/main ref locally, skipping the "is it on main" check.\n\n'
fi

# --- 5. Apple device targets that publish but that no workflow compiles.
#
# ci.yml and publish.yml name `compileKotlinIosArm64` per module, so a module
# that opts into Apple targets and is not added to both lists is compiled for
# the device target by nobody until the tag lands — and a red one of them fails
# the publish job partway through, after earlier modules have reached Central.
# Keep the lists honest here, where it costs a second.
PUBLISHING_MODULES=""
IOS_X64_TASKS=""
for build_file in */build.gradle.kts; do
    [ -f "$build_file" ] || continue
    grep -q 'mavenPublishing {' "$build_file" || continue

    module=":${build_file%/build.gradle.kts}"
    PUBLISHING_MODULES="$PUBLISHING_MODULES $module"

    grep -q 'iosArm64()' "$build_file" || continue

    if ! grep -q "$module:compileKotlinIosArm64" .github/workflows/ci.yml ||
        ! grep -q "$module:compileKotlinIosArm64" .github/workflows/publish.yml; then
        printf 'release-preflight: %s publishes Apple targets but %s:compileKotlinIosArm64 is missing\n' \
            "$module" "$module" >&2
        printf '  from .github/workflows/ci.yml or .github/workflows/publish.yml. Add it to both.\n' >&2
        exit 1
    fi

    # iosX64 is in every publication and in neither workflow. Compile it here.
    if grep -q 'iosX64()' "$build_file"; then
        IOS_X64_TASKS="$IOS_X64_TASKS $module:compileKotlinIosX64"
    fi
done

if [ -z "$PUBLISHING_MODULES" ]; then
    printf 'release-preflight: found no module declaring mavenPublishing { }. That cannot be right —\n' >&2
    printf '  the module layout moved and this script did not follow it.\n' >&2
    exit 1
fi

# --- 6. One build.
#
# ktlintCheck: cheap, and a lint failure is a red release for no reason.
# verifyPublishWorkflowCoverage: the module-to-publish-list drift check (root
#   build.gradle.kts). Seconds, no compile, and it is the one failure here that
#   would otherwise surface as a module missing from Central with a green run.
# jvmTest: the primary gate, every module.
# compileKotlinIosX64: the one published target no job produces before the tag.
#   Weakest of these checks — it shares its sources with the simulator target CI
#   already tests — but a native target that fails during the publish step fails
#   it partway, after earlier modules have reached Central.
#
# NOT here:
#
# jsTest / wasmJsTest — run on every PR, and they are the reason phosphor has
#   no "JVM-only API in commonMain" hole: commonMain is compiled for two targets
#   with no JVM stdlib before it ever reaches a release.
# dokkaGeneratePublicationHtml — the javadoc jars need it and publish.yml gates
#   on its link warnings, but that step runs BEFORE the publish step, so a
#   failure there costs a tag and not a version. CI runs the same task with the
#   same grep on every PR. Doubling the cost of this build to re-check a
#   recoverable failure is not worth it on a 16GB machine.
# iosSimulatorArm64Test — CI runs it on every PR, across every module.
TASKS="ktlintCheck verifyPublishWorkflowCoverage jvmTest$IOS_X64_TASKS"

printf 'release-preflight: validating %s at %s\n' "$EXPECTED_VERSION" "$(git rev-parse --short HEAD)"
printf 'release-preflight: publishing modules:%s\n' "$PUBLISHING_MODULES"
printf 'release-preflight: ./gradlew %s\n\n' "$TASKS"

# shellcheck disable=SC2086
./gradlew $TASKS

COMMIT=$(git rev-parse --short HEAD)
printf '\nrelease-preflight: PASS — v%s can be cut from %s.\n\n' "$EXPECTED_VERSION" "$COMMIT"
printf 'Next, and only if the line above says PASS:\n'
printf '  git tag v%s %s\n' "$EXPECTED_VERSION" "$COMMIT"
printf '  git push origin v%s\n' "$EXPECTED_VERSION"
