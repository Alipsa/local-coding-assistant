#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PROJECT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

if [ -z "$PROJECT_VERSION" ]; then
  echo "Error: Could not determine project version. Exiting." >&2
  exit 1
fi

if ! [[ "$PROJECT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
  echo "Error: Project version '$PROJECT_VERSION' is not semantic (x.y.z or x.y.z-SNAPSHOT)." >&2
  exit 1
fi

if [[ $(git status --porcelain) ]]; then
  echo "Git changes detected, commit all changes first before releasing"
  exit
fi
mvn -DrunSlowIntegrationTests=true -Prelease -B clean package site deploy
echo "Release to maven successful!"

. "$SCRIPT_DIR/ghrelease.sh"

# Add LOC count to release notes
echo "Adding LOC count to release.md..."
LOC_OUTPUT=$("$SCRIPT_DIR/locCount.sh" 2>&1)
LOC_TOTAL=$(echo "$LOC_OUTPUT" | grep "^Total:" | awk '{print $2}')
LOC_MAIN=$(echo "$LOC_OUTPUT" | grep "^Main source code:" | awk '{print $4}')
LOC_TEST=$(echo "$LOC_OUTPUT" | grep "^Test code:" | awk '{print $3}')

# Create LOC stats entry
LOC_ENTRY="**Lines of Code:** $LOC_TOTAL total ($LOC_MAIN main, $LOC_TEST test)"

# Find the line number of the second "## Version" occurrence (to insert before it)
SECOND_VERSION_LINE=$(grep -n "^## Version" "$SCRIPT_DIR/release.md" | sed -n '2p' | cut -d: -f1)

if [ -n "$SECOND_VERSION_LINE" ]; then
  # Insert LOC stats before the second version section using awk
  awk -v line="$SECOND_VERSION_LINE" -v entry="$LOC_ENTRY" '
    NR == line - 1 { print; print ""; print entry; next }
    { print }
  ' "$SCRIPT_DIR/release.md" > "$SCRIPT_DIR/release.md.tmp"
  mv "$SCRIPT_DIR/release.md.tmp" "$SCRIPT_DIR/release.md"
  echo "LOC count added to release.md for version $PROJECT_VERSION"
else
  # If there's only one version section, append at the end
  echo "" >> "$SCRIPT_DIR/release.md"
  echo "$LOC_ENTRY" >> "$SCRIPT_DIR/release.md"
  echo "LOC count appended to end of release.md for version $PROJECT_VERSION"
fi
