#!/bin/bash

# Count dependencies in the Local Coding Assistant project

echo "=========================================="
echo "Local Coding Assistant - Dependency Count"
echo "=========================================="
echo ""

# Temporary files for Maven output
COMPILE_TREE=$(mktemp)
TEST_TREE=$(mktemp)

# Get dependency tree for compile scope
echo "Analyzing compile dependencies..."
mvn dependency:tree -Dscope=compile -DoutputFile="$COMPILE_TREE" -DoutputType=text > /dev/null 2>&1

# Get dependency tree for test scope
echo "Analyzing test dependencies..."
mvn dependency:tree -Dscope=test -DoutputFile="$TEST_TREE" -DoutputType=text > /dev/null 2>&1

# Count compile dependencies
# Direct dependencies start with "+-" or "\-" at the first level
# Transitive dependencies have more nesting (multiple "|  " or "   " prefixes)
compile_direct=$(grep -E "^\+- |^\\\\- " "$COMPILE_TREE" | wc -l | awk '{print $1}')
compile_transitive=$(grep -E "^\|  |^   " "$COMPILE_TREE" | grep -E "\+- |\\\\- " | wc -l | awk '{print $1}')
compile_total=$((compile_direct + compile_transitive))

echo ""
echo "Main (compile scope):"
echo "  Direct dependencies:      $compile_direct"
echo "  Transitive dependencies:  $compile_transitive"
echo "  Total:                    $compile_total"

# Count test dependencies (includes compile + test scope)
test_direct=$(grep -E "^\+- |^\\\\- " "$TEST_TREE" | wc -l | awk '{print $1}')
test_transitive=$(grep -E "^\|  |^   " "$TEST_TREE" | grep -E "\+- |\\\\- " | wc -l | awk '{print $1}')
test_total=$((test_direct + test_transitive))

echo ""
echo "Test (test scope, includes compile):"
echo "  Direct dependencies:      $test_direct"
echo "  Transitive dependencies:  $test_transitive"
echo "  Total:                    $test_total"

# Clean up
rm -f "$COMPILE_TREE" "$TEST_TREE"

echo ""
echo "=========================================="
