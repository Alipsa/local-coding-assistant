#!/usr/bin/env bash
set -e
echo "Building project..."
mvn -q compile -DskipTests package
echo "Copy lca to .local/bin"
cp src/main/bin/lca $HOME/.local/bin/

FULL_NAME=$(ls -S target/*.jar | grep -vE ".original|javadoc|sources|groovydoc" | head -n 1 | xargs basename)

echo "Installing $FULL_NAME"
$HOME/.local/bin/lca install target/$FULL_NAME
echo "Done"