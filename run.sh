#!/usr/bin/env bash
set -e
echo "Building Local Coding Assistant..."
./mvnw -q -Dmaven.test.skip=true clean package
JARFILE=$(ls ./target/local-coding-assistant-*-exec.jar | head -n 1)
echo "Running Local Coding Assistant..."
java -jar "$JARFILE" "$@"