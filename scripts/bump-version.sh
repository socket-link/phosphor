#!/bin/sh
#
# bump-version.sh
#
# Closes out the release that was just tagged and opens the next cycle. Two
# facts move, and they move together in one commit:
#
#   gradle.properties   phosphorVersion=<next version>
#   CHANGELOG.md        ## [Unreleased]  ->  ## [<released version>] - <tag date>
#
#   scripts/bump-version.sh 0.8.0
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

if grep -q "^## \[$RELEASED_VERSION\]" CHANGELOG.md; then
    printf 'bump-version: CHANGELOG.md already has a "## [%s]" section.\n' "$RELEASED_VERSION" >&2
    printf '  That release is already closed out. Reconcile by hand; this script will not roll twice.\n' >&2
    exit 1
fi

if ! grep -q '^## \[Unreleased\]' CHANGELOG.md; then
    printf 'bump-version: CHANGELOG.md has no "## [Unreleased]" heading to roll.\n' >&2
    printf '  Add one back above the newest version section, then re-run.\n' >&2
    exit 1
fi

unreleased_body() {
    awk '/^## \[Unreleased\]/ { capture = 1; next } /^## / { capture = 0 } capture'
}

if [ -z "$(unreleased_body < CHANGELOG.md | tr -d ' \t\n')" ]; then
    printf 'bump-version: CHANGELOG.md has nothing under [Unreleased] to attribute to %s.\n' \
        "$RELEASED_VERSION" >&2
    printf '  Either the release documented no change, or the entries were already moved.\n' >&2
    exit 1
fi

# The date in the changelog should be the day the artifact shipped. Under this
# convention the tagged commit can be much older than the release — you tag a
# commit from before the next bump — so its commit date is the wrong answer, and
# a lightweight tag carries no date of its own. The GitHub Release that
# publish.yml creates does, so ask for that and fall back to today, which is
# close when the close-out PR is opened promptly (and it should be).
ROLL_DATE=$(
    gh release view "v$RELEASED_VERSION" --json publishedAt -q '.publishedAt' 2>/dev/null |
        cut -c1-10
)
if [ -n "$ROLL_DATE" ]; then
    ROLL_DATE_SOURCE="the v$RELEASED_VERSION GitHub Release"
else
    ROLL_DATE=$(date +%Y-%m-%d)
    ROLL_DATE_SOURCE="today (no GitHub Release found for v$RELEASED_VERSION)"
fi

# Entries that landed under [Unreleased] AFTER the tag belong to the next cycle,
# not to the release being closed out. The roll cannot tell them apart — the
# section groups by kind (### Added, ### Fixed) with newest first, so the
# difference is not a contiguous slice — but it can say that they exist, and
# which commits brought them.
TAGGED_BODY=$(git show "v$RELEASED_VERSION:CHANGELOG.md" 2>/dev/null | unreleased_body || true)
CURRENT_BODY=$(unreleased_body < CHANGELOG.md)
TRAILING_COMMITS=$(git log --oneline "v$RELEASED_VERSION..HEAD" -- CHANGELOG.md || true)

# In-place editing without `sed -i`, which takes an argument on BSD/macOS and
# not on GNU. Write beside the file, then move over it.
sed "s/^phosphorVersion=.*$/phosphorVersion=$NEW_VERSION/" gradle.properties > gradle.properties.bump-tmp
mv gradle.properties.bump-tmp gradle.properties

# Leave an empty [Unreleased] behind for the next cycle and open the dated
# section directly under it, so the entries that were unreleased become this
# version's without being moved.
awk -v version="$RELEASED_VERSION" -v date="$ROLL_DATE" '
    !rolled && /^## \[Unreleased\]/ {
        print
        print ""
        print "## [" version "] - " date
        rolled = 1
        next
    }
    { print }
' CHANGELOG.md > CHANGELOG.md.bump-tmp
mv CHANGELOG.md.bump-tmp CHANGELOG.md

# Both edits are anchored, so a file whose shape moved would silently not match.
# Read the result back rather than trusting the write.
GRADLE_NOW=$(sed -n 's/^phosphorVersion=//p' gradle.properties)
if [ "$GRADLE_NOW" != "$NEW_VERSION" ]; then
    printf 'bump-version: gradle.properties edit did not take (reads %s).\n' "$GRADLE_NOW" >&2
    printf '  The phosphorVersion declaration moved or changed shape. Fix it by hand, then update\n' >&2
    printf '  this script, scripts/release-preflight.sh, and the tag check in publish.yml.\n' >&2
    exit 1
fi

if ! grep -Fq "## [$RELEASED_VERSION] - $ROLL_DATE" CHANGELOG.md; then
    printf 'bump-version: CHANGELOG.md roll did not take.\n' >&2
    printf '  The [Unreleased] heading moved or changed shape. Roll it by hand, then update this\n' >&2
    printf '  script and the changelog check in scripts/release-preflight.sh.\n' >&2
    exit 1
fi

printf 'bump-version: closed out %s (dated %s, from %s), main now carries %s\n\n' \
    "$RELEASED_VERSION" "$ROLL_DATE" "$ROLL_DATE_SOURCE" "$NEW_VERSION"
git --no-pager diff --stat -- gradle.properties CHANGELOG.md
printf '\n'
git --no-pager diff -- gradle.properties

if [ "$TAGGED_BODY" != "$CURRENT_BODY" ]; then
    printf '\n'
    printf '  WARNING: [Unreleased] has changed since v%s was tagged, so some of what was just\n' \
        "$RELEASED_VERSION"
    printf '  rolled into [%s] landed after the release and belongs under [Unreleased].\n' \
        "$RELEASED_VERSION"
    printf '  Commits touching CHANGELOG.md after the tag:\n\n'
    printf '%s\n' "$TRAILING_COMMITS" | sed 's/^/    /'
    printf '\n  Move those entries back up before committing.\n'
fi

printf '\nRead the rolled section before committing — the roll moves headings, not prose, and a\n'
printf 'section written across several PRs usually needs an edit to read as one release.\n'
printf '\nNext:\n'
printf '  git commit -am "Bump version to %s, close out %s"\n' "$NEW_VERSION" "$RELEASED_VERSION"
printf '  gh pr create --base main --title "Bump version to %s, close out %s"\n' \
    "$NEW_VERSION" "$RELEASED_VERSION"
