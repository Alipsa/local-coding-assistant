#!/usr/bin/env bash
set -euo pipefail

JAR_PATH="${1:-local-coding-assistant.jar}"
COMMAND_FILE="${2:-docs/examples/batch-basic.txt}"

java -jar "$JAR_PATH" --batch-file "$COMMAND_FILE"
