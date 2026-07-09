#!/usr/bin/env bash
# Tests for run_with_timeout. Run directly: bash demo/lib/test_timeout.sh
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./timeout.sh

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

# Test 1: command finishes well before the timeout -> real exit code, fast.
start=$(date +%s)
run_with_timeout 5 2 -- bash -c 'exit 7'
status=$?
elapsed=$(( $(date +%s) - start ))
check "fast command returns its own exit code" "7" "$status"
check "fast command does not wait for the timeout" "1" "$([[ $elapsed -le 2 ]] && echo 1 || echo 0)"

# Test 1b: verify no lingering sleep process from watchdog leak (process group kill fix).
# Capture process count before and after a fast-exit invocation with a timeout;
# if the watchdog's sleep child is killed properly, no extra processes should remain.
pre_count=$(pgrep -c sleep 2>/dev/null || echo 0)
run_with_timeout 10 2 -- bash -c 'exit 0' >/dev/null 2>&1
# Give any leaked processes a moment to be sure they're not in a race condition
sleep 0.5
post_count=$(pgrep -c sleep 2>/dev/null || echo 0)
leaked=$((post_count - pre_count))
check "no lingering watchdog sleep on fast-exit path" "0" "$leaked"

# Test 2: command exceeds the timeout but responds to SIGTERM promptly.
start=$(date +%s)
run_with_timeout 1 2 -- sleep 10
status=$?
elapsed=$(( $(date +%s) - start ))
check "TERM-responsive overrun returns 124" "124" "$status"
check "TERM-responsive overrun does not wait out the full sleep" "1" "$([[ $elapsed -le 3 ]] && echo 1 || echo 0)"

# Test 3: command exceeds the timeout AND ignores SIGTERM -> must escalate to SIGKILL.
start=$(date +%s)
run_with_timeout 1 1 -- bash -c 'trap "" TERM; sleep 10'
status=$?
elapsed=$(( $(date +%s) - start ))
check "SIGTERM-ignoring overrun still returns 124" "124" "$status"
check "SIGTERM-ignoring overrun is killed near timeout+grace, not the full sleep" "1" "$([[ $elapsed -le 4 ]] && echo 1 || echo 0)"

# Test 4: killing must reach grandchildren (whole process group), not just the direct child.
marker=$(mktemp)
rm -f "$marker"
run_with_timeout 1 1 -- bash -c "sleep 10 & echo \$! > $marker; wait" >/dev/null 2>&1
grandchild_pid=$(cat "$marker" 2>/dev/null || echo "")
rm -f "$marker"
if [[ -n "$grandchild_pid" ]] && kill -0 "$grandchild_pid" 2>/dev/null; then
  check "grandchild process is also reaped" "dead" "alive"
else
  check "grandchild process is also reaped" "dead" "dead"
fi

echo "---"
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
