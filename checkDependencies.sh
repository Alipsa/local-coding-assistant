#!/usr/bin/env bash
set -e  # Exit on error

if ! command -v mvn &> /dev/null; then
  echo "Error: Maven (mvn) is not installed or not in PATH"
  exit 1
fi
mvn -B versions:display-plugin-updates versions:display-property-updates