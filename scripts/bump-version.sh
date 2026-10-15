#!/bin/sh
#
# bump-version.sh
#
# Opens the next cycle after a release is tagged, by moving one fact:
#
#   gradle.properties   phosphorVersion=<next version>
#
#   scripts/bump-version.sh 0.8.0
#
# There is no changelog to roll. Release notes are generated from commit/PR
# history when publish.yml creates the GitHub Release — see AGENTS.md#changelog.
#
# The argument is the NEXT, unreleased version. The version being closed out is
# the one already in gradle.properties — `main` carries the next unreleased
# version, so by the time you run this, the tag for the old number already
# exists and this script refuses to run if it does not.
#
# Run it on a branch, not on main, and open the PR right after the tag lands.
# See docs/RELEASING.md step 5.

set -eu

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$REPO_ROOT" ]; then
    printf 'bump-version: not inside a git repository.\n' >&2
    exit 1
fi
cd "$REPO_ROOT"

NEW_VERSION=${1:-}
if [ -z "$NEW_VERSION" ]; then
    printf 'bump-version: usage: scripts/bump-version.sh <next-version>   (e.g. 0.8.0)\n' >&2
    exit 1
fi

# Three numeric components, no suffix. Not a style preference: publish.yml
# compares the tag to this string exactly, so a `-SNAPSHOT` suffix on main would
# make the next tag's validation step fail.
if ! printf '%s' "$NEW_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    printf 'bump-version: "%s" is not of the form MAJOR.MINOR.PATCH with no suffix.\n' "$NEW_VERSION" >&2
    exit 1
fi

RELEASED_VERSION=$(sed -n 's/^phosphorVersion=//p' gradle.properties)

if [ "$NEW_VERSION" = "$RELEASED_VERSION" ]; then
    printf 'bump-version: already at %s; nothing to do.\n' "$NEW_VERSION" >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/v$NEW_VERSION" >/dev/null; then
    printf 'bump-version: v%s is already released. Bump past it, not onto it.\n' "$NEW_VERSION" >&2
    exit 1
fi

# The version on main must already be tagged. This is the guard that keeps the
# convention honest: a bump that runs before the tag would move main off the
# number the release is supposed to carry, and publish.yml would then refuse the
# tag. If this fires, you are bumping too early — tag first.
if ! git rev-parse -q --verify "refs/tags/v$RELEASED_VERSION" >/dev/null; then
    printf 'bump-version: gradle.properties declares %s but v%s was never tagged.\n' \
        "$RELEASED_VERSION" "$RELEASED_VERSION" >&2
    printf '  main carries the NEXT unreleased version, so the bump happens AFTER the tag, not\n' >&2
    printf '  before it. Release v%s first, then come back and open the next cycle.\n' "$RELEASED_VERSION" >&2
    printf '  See docs/RELEASING.md, "How the version moves".\n' >&2
    exit 1
fi

# In-place editing without `sed -i`, which takes an argument on BSD/macOS and
# not on GNU. Write beside the file, then move over it.
sed "s/^phosphorVersion=.*$/phosphorVersion=$NEW_VERSION/" gradle.properties > gradle.properties.bump-tmp
mv gradle.properties.bump-tmp gradle.properties

# The edit is anchored, so a file whose shape moved would silently not match.
# Read the result back rather than trusting the write.
GRADLE_NOW=$(sed -n 's/^phosphorVersion=//p' gradle.properties)
if [ "$GRADLE_NOW" != "$NEW_VERSION" ]; then
    printf 'bump-version: gradle.properties edit did not take (reads %s).\n' "$GRADLE_NOW" >&2
    printf '  The phosphorVersion declaration moved or changed shape. Fix it by hand, then update\n' >&2
    printf '  this script, scripts/release-preflight.sh, and the tag check in publish.yml.\n' >&2
    exit 1
fi

printf 'bump-version: %s -> %s\n\n' "$RELEASED_VERSION" "$NEW_VERSION"
git --no-pager diff -- gradle.properties

printf '\nNext:\n'
printf '  git commit -am "Bump version to %s"\n' "$NEW_VERSION"
printf '  gh pr create --base main --title "Bump version to %s"\n' "$NEW_VERSION"
