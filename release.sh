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
