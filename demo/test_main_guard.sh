#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"

pass=0
fail=0
check() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: $desc"
    pass=$((pass + 1))
  else
    echo "FAIL: $desc (expected [$expected], got [$actual])"
    fail=$((fail + 1))
  fi
}

# Sourcing must produce zero output and exit 0 -- no side effects at all.
output=$(source ./openCodeMlx 2>&1)
status=$?
check "sourcing produces no output" "" "$output"
check "sourcing exits 0" "0" "$status"

# main must be defined as a callable function after sourcing.
(
  source ./openCodeMlx
  declare -F main >/dev/null 2>&1
)
check "main is defined as a function after sourcing" "0" "$?"

# The guard must actually invoke main when the file is executed, not sourced.
if grep -qE '^\s*main "\$@"\s*$' ./openCodeMlx; then
  echo "PASS: guard invokes main \"\$@\""
  pass=$((pass + 1))
else
  echo "FAIL: guard invoking main \"\$@\" not found"
  fail=$((fail + 1))
fi

echo "---"
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
