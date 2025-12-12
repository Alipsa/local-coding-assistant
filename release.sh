#!/usr/bin/env bash
set -e
if [[ $(git status --porcelain) ]]; then
  echo "Git changes detected, commit all changes first before releasing"
  exit
fi
mvn -Prelease -B clean package site deploy
echo "Release to maven successful!"

echo "Starting GitHub release process..."

# --- Configuration ---
# Set the maximum number of log entries to include if no previous tag is found
LOG_LIMIT=10

# --- Function to Check for Required Commands ---
check_commands() {
    for cmd in gh mvn git; do
        if ! command -v "$cmd" &> /dev/null; then
            echo "Error: Required command '$cmd' is not installed or not in your PATH." >&2
            exit 1
        fi
    done
}

# --- Main Script Execution ---
check_commands

echo "1. Reading project version from pom.xml..."

# Use Maven help:evaluate to reliably extract the project version
PROJECT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

if [ -z "$PROJECT_VERSION" ]; then
    echo "Error: Could not determine project version. Exiting." >&2
    exit 1
fi

# Define variables based on the extracted version
RELEASE_TAG="v$PROJECT_VERSION"
RELEASE_TITLE="Version $PROJECT_VERSION"

echo "   -> Detected Version: $PROJECT_VERSION"

# 2. Determine release type (prerelease or official)
RELEASE_FLAGS=""
if [[ "$PROJECT_VERSION" == *"-SNAPSHOT"* ]]; then
    echo "   -> Version contains '-SNAPSHOT'. Setting release as **--prerelease**."
    RELEASE_FLAGS="--prerelease"
else
    echo "   -> Version is a stable release. Creating a normal release."
fi

# 3. Generate Release Notes from Git Log
echo "3. Determining range for release notes..."

# Find the most recent tag to set the start point for the git log
# 2> /dev/null suppresses the error message if no tag is found
LAST_TAG=$(git describe --tags --abbrev=0 2> /dev/null)

if [ -z "$LAST_TAG" ]; then
    echo "   -> Warning: No previous Git tag found. Including last $LOG_LIMIT non-merge commits."
    # If no tag is found, limit the history
    LOG_RANGE="-n $LOG_LIMIT"
else
    echo "   -> Found last tag: $LAST_TAG. Including commits from this point onwards."
    # Set the range to be commits AFTER the last tag up to HEAD
    LOG_RANGE="${LAST_TAG}..HEAD"
fi

# Generate the commit log:
# --no-merges: Excludes all merge commits (the new requirement)
# --pretty=format:"* %s (%an)": Formats each commit as a bullet point with subject and author
RELEASE_NOTES=$(git log --no-merges --pretty=format:"%s" "$LOG_RANGE")

# Check if RELEASE_NOTES is empty
if [ -z "$RELEASE_NOTES" ]; then
    echo "   -> No new non-merge commits found in the range. Using a generic message."
    RELEASE_NOTES="* No feature or bug fix commits since the last release. Includes internal updates."
fi


# Create a temporary file for the notes (required for gh CLI input)
NOTES_FILE=$(mktemp)
echo "### Changes in $RELEASE_TITLE" > "$NOTES_FILE"
echo "" >> "$NOTES_FILE"
echo "$RELEASE_NOTES" >> "$NOTES_FILE"

echo "   -> Notes saved to temporary file: $NOTES_FILE"

# 4. Create the Release with gh CLI
echo "4. Creating GitHub Release..."

# The gh CLI command remains the same
gh release create "$RELEASE_TAG" \
    --title "$RELEASE_TITLE" \
    --notes-file "$NOTES_FILE" \
    --latest \
    $RELEASE_FLAGS

# 5. Clean Up
rm "$NOTES_FILE"

if [ $? -eq 0 ]; then
    echo "---"
    echo "✅ Success! Release '$RELEASE_TAG' created on GitHub."
    echo "---"
else
    echo "---"
    echo "❌ Error: Failed to create GitHub release. Check the output above."
    echo "---"
fi