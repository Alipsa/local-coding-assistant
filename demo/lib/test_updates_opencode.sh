#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./updates.sh

# Test helper for Scenario E: verify set -e doesn't abort on failed install
test_set_e_with_failed_install() {
  local test_home
  local output
  test_home=$(mktemp -d)
  trap "rm -rf '$test_home'" RETURN

  # Create a script that runs the test. This allows us to capture output properly.
  local test_script
  test_script=$(mktemp)
  trap "rm -f '$test_script'" RETURN

  cat > "$test_script" <<'TESTEOF'
set -e
source ./test_helpers.sh
source ./updates.sh
_install_opencode_via_curl() { return 1; }
PATH="/usr/bin:/bin:/usr/local/bin" HOME="$1" ensure_opencode_current 2>&1
TESTEOF

  # Run it; with the bug (bare call), set -e aborts before printing error.
  # With the fix (|| true), the function completes and prints the error.
  output=$(bash "$test_script" "$test_home" 2>&1)
  local exit_code=$?

  # The fixed version: exit code 1 and message printed (reached error path)
  # The buggy version: exit code 1 but message NOT printed (aborted by set -e)
  if echo "$output" | grep -q "opencode installation failed"; then
    return 0
  fi
  return 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Scenarios A/B below use a real (non-empty) PATH restricted to standard
# system dirs, not a PATH-stubbed "curl"/"bash": some sandboxed shells
# intercept commands literally named curl/bash by identity and hang rather
# than erroring, even when PATH-shadowed by a harmless local stub. The
# install step itself is exercised via _install_opencode_via_curl (see
# Step 3), which tests override directly as a bash function instead.
REAL_PATH="/usr/bin:/bin:/usr/local/bin"

# Scenario A: opencode not on PATH; the install override actually creates a
# working $HOME/.opencode/bin/opencode (simulating a real successful install).
home_a="$work/a_home"
_install_opencode_via_curl() {
  mkdir -p "$home_a/.opencode/bin"
  cat > "$home_a/.opencode/bin/opencode" <<'INNER'
#!/usr/bin/env bash
exit 0
INNER
  chmod +x "$home_a/.opencode/bin/opencode"
}
(
  PATH="$REAL_PATH" HOME="$home_a" ensure_opencode_current >/dev/null 2>&1
)
check "not-installed + install produces a binary -> returns 0" "0" "$?"

# Scenario B: opencode not on PATH; the install override is a no-op
# (simulates a failed install -- opencode still isn't findable afterward).
home_b="$work/b_home"
_install_opencode_via_curl() { :; }
(
  PATH="$REAL_PATH" HOME="$home_b" ensure_opencode_current >/dev/null 2>&1
)
check "not-installed + install produces no binary -> returns 1 (hard error)" "1" "$?"

# Scenario C: opencode already on PATH, `opencode upgrade` stub succeeds.
bin_c="$work/c"; mkdir -p "$bin_c"
log_c="$work/c.log"
make_stub "$bin_c" opencode 0 "$log_c"
(
  PATH="$bin_c:$PATH" ensure_opencode_current >/dev/null 2>&1
)
status_c=$?
check "already-installed + upgrade succeeds -> returns 0" "0" "$status_c"
check "already-installed + upgrade succeeds -> opencode upgrade was invoked" "1" "$(grep -c '^opencode upgrade$' "$log_c")"

# Scenario D: opencode already on PATH, `opencode upgrade` stub fails -> still returns 0 (warn+continue).
bin_d="$work/d"; mkdir -p "$bin_d"
log_d="$work/d.log"
make_stub "$bin_d" opencode 1 "$log_d"
(
  PATH="$bin_d:$PATH" ensure_opencode_current >/dev/null 2>&1
)
check "already-installed + upgrade fails -> still returns 0 (warn and continue)" "0" "$?"

# Scenario E: opencode not on PATH, install fails (returns 1), ensure_opencode_current
# is called under `set -e`. Verify that the function's return 1 is reached (not aborted
# by set -e when _install_opencode_via_curl exits with status 1). The function should
# return 1 to the outer subshell (not die uncontrolled).
test_set_e_with_failed_install
check "install fails under set -e -> function returns 1 (not aborted by set -e)" "0" "$?"

report
