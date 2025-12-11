#!/usr/bin/env bash
if [[ $(git status --porcelain) ]]; then
  echo "Git changes detected, commit all changes first before releasing"
  exit
fi
mvn -Prelease -B clean site deploy || exit 1
echo "Release to maven successful!"

echo "Starting GitHub release process..."

# --- Configuration ---
# Set the maximum number of log entries to include in the release notes
LOG_LIMIT=10

# --- Function to Check for Required Commands ---
check_commands() {
    for cmd in gh mvn; do
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
# The -q (quiet) and -DforceStdout flags ensure only the version number is printed
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
echo "3. Generating release notes from the last $LOG_LIMIT commits..."

# Get the commit history:
# --pretty=format:"* %s (%an)" -> Formats each commit as a bullet point with subject and author
# -n $LOG_LIMIT -> Limits the output to the last $LOG_LIMIT commits
RELEASE_NOTES=$(git log --pretty=format:"* %s (%an)" -n "$LOG_LIMIT")

# Create a temporary file for the notes (required for gh CLI input)
NOTES_FILE=$(mktemp)
echo "### Changes in $RELEASE_TITLE" > "$NOTES_FILE"
echo "" >> "$NOTES_FILE"
echo "$RELEASE_NOTES" >> "$NOTES_FILE"

echo "   -> Notes saved to temporary file: $NOTES_FILE"

# 4. Create the Release with gh CLI
echo "4. Creating GitHub Release..."

# Note: The gh CLI will automatically create the Git tag if it doesn't exist.
# It uses the target of the HEAD of the current branch by default.
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