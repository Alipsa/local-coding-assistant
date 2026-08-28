#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Stub dirs are prepended to a real system PATH, not used exclusively:
# ensure_mlx_vlm_current itself shells out to other real commands, which an
# exclusive stub-only PATH would hide too.
REAL_PATH="/usr/bin:/bin:/usr/local/bin"

# Scenario A: mlx-vlm missing, pip install stub succeeds -> returns 0, and pip is invoked with
# the same transformers pin ensure_mlx_lm_current carries. This is the actual fix under test:
# both packages share one venv, so an unconstrained "pip install mlx-vlm" is free to upgrade
# transformers past the version mlx-lm's AutoTokenizer.register call tolerates, silently
# breaking mlx_lm.server (still used unconditionally for the small background model).
bin_a="$work/a"; mkdir -p "$bin_a"
log_a="$work/a.log"
make_stub "$bin_a" pip 0 "$log_a"
# no python3 stub -> `python3 -m mlx_vlm.server --help` fails against the real system
# interpreter (which has no mlx_vlm module), simulating "not installed"
(
  PATH="$bin_a:$REAL_PATH" ensure_mlx_vlm_current >/dev/null 2>&1
)
check "not-installed + pip install succeeds -> returns 0" "0" "$?"
check "not-installed -> pip carries the shared transformers pin" "1" "$(grep -c 'transformers>=5.7,<5.13' "$log_a")"

# Scenario B: mlx-vlm missing, pip install stub fails -> hard error (returns 1).
bin_b="$work/b"; mkdir -p "$bin_b"
log_b="$work/b.log"
make_stub "$bin_b" pip 1 "$log_b"
(
  PATH="$bin_b:$REAL_PATH" ensure_mlx_vlm_current >/dev/null 2>&1
)
check "not-installed + pip install fails -> returns 1 (hard error)" "1" "$?"

# Scenario C: mlx-vlm present, pip install stub fails -> still returns 0 (warn+continue).
bin_c="$work/c"; mkdir -p "$bin_c"
log_c="$work/c.log"
make_stub "$bin_c" python3 0 "$log_c"
make_stub "$bin_c" pip 1 "$log_c"
(
  PATH="$bin_c:$REAL_PATH" ensure_mlx_vlm_current >/dev/null 2>&1
)
check "already-installed + pip update fails -> still returns 0 (warn and continue)" "0" "$?"

# Scenario D: mlx-vlm present, pip install stub succeeds -> pip was still invoked (not skipped)
# and still carries the transformers pin.
bin_d="$work/d"; mkdir -p "$bin_d"
log_d="$work/d.log"
make_stub "$bin_d" python3 0 "$log_d"
make_stub "$bin_d" pip 0 "$log_d"
(
  PATH="$bin_d:$REAL_PATH" ensure_mlx_vlm_current >/dev/null 2>&1
)
check "already-installed + pip update succeeds -> returns 0" "0" "$?"
check "already-installed -> pip is still invoked (update not skipped)" "1" "$(grep -c '^pip ' "$log_d")"
check "already-installed -> pip update still carries the shared transformers pin" "1" "$(grep -c 'transformers>=5.7,<5.13' "$log_d")"

# Scenario E: mlx-vlm present, called in install-only mode -> pip upgrade must NOT be attempted.
bin_e="$work/e"; mkdir -p "$bin_e"
log_e="$work/e.log"
make_stub "$bin_e" python3 0 "$log_e"
make_stub "$bin_e" pip 1 "$log_e"
(
  PATH="$bin_e:$REAL_PATH" ensure_mlx_vlm_current install-only >/dev/null 2>&1
)
check "already-installed + install-only mode -> returns 0" "0" "$?"
check "already-installed + install-only mode -> pip was NOT invoked" "0" "$(grep -c '^pip ' "$log_e")"

report
