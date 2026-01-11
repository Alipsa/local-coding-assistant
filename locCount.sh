#!/bin/bash

# Count lines of code in the Local Coding Assistant project

echo "==================================="
echo "Local Coding Assistant - LOC Count"
echo "==================================="
echo ""

# Count main source code
if [ -d "src/main" ]; then
    main_loc=$(find src/main -name "*.groovy" -o -name "*.java" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
    echo "Main source code:  $main_loc lines"
else
    echo "Main source code:  Directory not found"
    main_loc=0
fi

# Count test code
if [ -d "src/test" ]; then
    test_loc=$(find src/test -name "*.groovy" -o -name "*.java" | xargs wc -l 2>/dev/null | tail -1 | awk '{print $1}')
    echo "Test code:         $test_loc lines"
else
    echo "Test code:         Directory not found"
    test_loc=0
fi

# Calculate total
total_loc=$((main_loc + test_loc))
echo "-----------------------------------"
echo "Total:             $total_loc lines"
echo "==================================="
