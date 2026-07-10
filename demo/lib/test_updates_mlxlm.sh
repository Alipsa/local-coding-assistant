#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./updates.sh

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Stub dirs are prepended to a real system PATH, not used exclusively:
# run_with_timeout itself shells out to real mktemp/sleep/kill, which an
# exclusive stub-only PATH would hide too.
REAL_PATH="/usr/bin:/bin:/usr/local/bin"

# Scenario A: mlx_lm.server missing, pip install stub succeeds -> returns 0.
bin_a="$work/a"; mkdir -p "$bin_a"
log_a="$work/a.log"
make_stub "$bin_a" pip 0 "$log_a"
# no mlx_lm.server stub -> `command -v`/direct exec fails, simulating "not installed"
(
  PATH="$bin_a:$REAL_PATH" ensure_mlx_lm_current >/dev/null 2>&1
)
check "not-installed + pip install succeeds -> returns 0" "0" "$?"
check "not-installed + pip install succeeds -> pip was invoked with --upgrade" "1" "$(grep -c -- '--upgrade' "$log_a")"

# Scenario B: mlx_lm.server missing, pip install stub fails -> hard error (returns 1).
bin_b="$work/b"; mkdir -p "$bin_b"
log_b="$work/b.log"
make_stub "$bin_b" pip 1 "$log_b"
(
  PATH="$bin_b:$REAL_PATH" ensure_mlx_lm_current >/dev/null 2>&1
)
check "not-installed + pip install fails -> returns 1 (hard error)" "1" "$?"

# Scenario C: mlx_lm.server present, pip install stub fails -> still returns 0 (warn+continue).
bin_c="$work/c"; mkdir -p "$bin_c"
log_c="$work/c.log"
make_stub "$bin_c" mlx_lm.server 0 "$log_c"
make_stub "$bin_c" pip 1 "$log_c"
(
  PATH="$bin_c:$REAL_PATH" ensure_mlx_lm_current >/dev/null 2>&1
)
check "already-installed + pip update fails -> still returns 0 (warn and continue)" "0" "$?"

# Scenario D: mlx_lm.server present, pip install stub succeeds -> pip was still invoked (not skipped).
bin_d="$work/d"; mkdir -p "$bin_d"
log_d="$work/d.log"
make_stub "$bin_d" mlx_lm.server 0 "$log_d"
make_stub "$bin_d" pip 0 "$log_d"
(
  PATH="$bin_d:$REAL_PATH" ensure_mlx_lm_current >/dev/null 2>&1
)
check "already-installed + pip update succeeds -> returns 0" "0" "$?"
check "already-installed -> pip is still invoked (update not skipped)" "1" "$(grep -c '^pip ' "$log_d")"

report
