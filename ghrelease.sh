#!/usr/bin/env bash

set -e
echo "Starting GitHub release process..."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"
cd "$REPO_ROOT"

# --- Configuration ---
LOG_LIMIT=10
RELEASE_MD_PATH="$REPO_ROOT/release.md"

# --- Function to Check for Required Commands ---
check_commands() {
    for cmd in gh mvn git awk; do
        if ! command -v "$cmd" &> /dev/null; then
            echo "Error: Required command '$cmd' is not installed or not in your PATH." >&2
            exit 1
        fi
    done
}

# --- Main Script Execution ---
check_commands

echo "1. Reading project version from pom.xml..."

PROJECT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
PROJECT_ARTIFACT_ID=$(mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout)

if [ -z "$PROJECT_VERSION" ]; then
    echo "Error: Could not determine project version." >&2
    exit 1
fi
if [ -z "$PROJECT_ARTIFACT_ID" ]; then
    echo "Error: Could not determine project artifactId." >&2
    exit 1
fi
if ! [[ "$PROJECT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
    echo "Error: Project version '$PROJECT_VERSION' is not semantic (x.y.z or x.y.z-SNAPSHOT)." >&2
    exit 1
fi

RELEASE_TAG="v$PROJECT_VERSION"
RELEASE_TITLE="Version $PROJECT_VERSION"
EXEC_JAR_PATH="target/${PROJECT_ARTIFACT_ID}-${PROJECT_VERSION}-exec.jar"

echo "   -> Detected Version: $PROJECT_VERSION"
echo "   -> Detected Artifact ID: $PROJECT_ARTIFACT_ID"

# 2. Determine release type
RELEASE_FLAGS=""
LATEST_FLAG="--latest"
if [[ "$PROJECT_VERSION" == *"-SNAPSHOT"* ]]; then
    echo "   -> Version contains '-SNAPSHOT'. Marking as prerelease."
    RELEASE_FLAGS="--prerelease"
    LATEST_FLAG=""  # Don't mark prereleases as latest
fi

# 3. Prepare Release Notes
echo "3. Preparing release notes..."

RELEASE_NOTES=""
RELEASE_NOTES_SOURCE=""

if [ -f "$RELEASE_MD_PATH" ]; then
    RELEASE_NOTES=$(awk -v ver="$PROJECT_VERSION" '
      /^##[[:space:]]+Version[[:space:]]/ {
        if (found) exit
        if (index($0, ver) > 0) {
          found=1
          next
        }
      }
      found && /^##[[:space:]]+Version[[:space:]]/ {exit}
      found {print}
    ' "$RELEASE_MD_PATH")

    if [ -n "$RELEASE_NOTES" ]; then
        echo "   -> Found release notes in release.md for version $PROJECT_VERSION."
        RELEASE_NOTES_SOURCE="release.md"
    fi
fi

# --- FALLBACK: build notes from git log AND WRITE THEM TO release.md ---
if [ -z "$RELEASE_NOTES_SOURCE" ]; then
    echo "   -> No entry for this version in release.md. Building notes from git log."

    LAST_TAG=$(git describe --tags --abbrev=0 2> /dev/null)

    if [ -z "$LAST_TAG" ]; then
        echo "     -> No previous tag found. Using last $LOG_LIMIT commits."
        LOG_RANGE="-n $LOG_LIMIT"
    else
        echo "     -> Found last tag: $LAST_TAG"
        LOG_RANGE="${LAST_TAG}..HEAD"
    fi

    RELEASE_NOTES=$(git log --no-merges --pretty=format:"* %s" "$LOG_RANGE")

    if [ -z "$RELEASE_NOTES" ]; then
        RELEASE_NOTES="* No feature or bug fix commits since the last release."
    fi

    echo "   -> Writing new entry to release.md..."

    TMP_RELEASE_MD="$(mktemp)"
    {
        echo "## Version $PROJECT_VERSION"
        echo
        echo "$RELEASE_NOTES"
        echo
        [ -f "$RELEASE_MD_PATH" ] && cat "$RELEASE_MD_PATH"
    } > "$TMP_RELEASE_MD"

    mv "$TMP_RELEASE_MD" "$RELEASE_MD_PATH"

    RELEASE_NOTES_SOURCE="git log"
fi

# --- Write notes file for gh ---
NOTES_FILE="$(mktemp)"
trap 'rm -f "$NOTES_FILE"' EXIT

if [ "$RELEASE_NOTES_SOURCE" = "release.md" ]; then
    echo "$RELEASE_NOTES" > "$NOTES_FILE"
else
    echo "### Changes in $RELEASE_TITLE" > "$NOTES_FILE"
    echo >> "$NOTES_FILE"
    echo "$RELEASE_NOTES" >> "$NOTES_FILE"
fi

echo "   -> Notes prepared."

# 4. Create GitHub Release
echo "4. Creating GitHub release..."

gh release create "$RELEASE_TAG" \
    --title "$RELEASE_TITLE" \
    --notes-file "$NOTES_FILE" \
    $LATEST_FLAG \
    $RELEASE_FLAGS

# 5. Upload exec jar
echo "5. Uploading executable jar..."

if [ ! -f "$EXEC_JAR_PATH" ]; then
    echo "Error: Executable jar not found at $EXEC_JAR_PATH" >&2
    exit 1
fi

gh release upload "$RELEASE_TAG" "$EXEC_JAR_PATH" --clobber
echo "  Uploading lca scripts..."
gh release upload "$RELEASE_TAG" src/main/bin/lca --clobber

echo "---"
echo "✅ Release '$RELEASE_TAG' created and release.md updated."
echo "---"
