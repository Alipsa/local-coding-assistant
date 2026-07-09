# openCodeMlx Update-Checking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `demo/openCodeMlx` check for and apply updates to opencode, mlx-lm, and the model weights on every launch, instead of only installing them once.

**Architecture:** Extract the three "ensure X is current" behaviors into small sourceable bash libraries under `demo/lib/` (`timeout.sh`, `updates.sh`) so each can be unit-tested in isolation with PATH-stubbed fake commands, without executing the rest of the launcher (which does real sudo/GPU/network/TUI work that can't run in a test). `demo/openCodeMlx` sources these libraries and calls the new functions in place of its old one-shot install guards.

**Tech Stack:** bash (macOS/BSD tools only — no GNU coreutils `timeout`), existing `pip`/`opencode`/`python3` (huggingface_hub, modelscope) tooling already used by the script.

## Global Constraints

- The script runs under `set -e` (line 7 of `demo/openCodeMlx`) — every update command must have its exit status explicitly handled (`if ! cmd; then ...; fi`), never left bare.
- Each component captures an "already present" boolean *before* attempting its update; that flag decides hard-error-vs-warn on failure, it does not gate whether the update is attempted.
- A failed update on an already-present component warns to stderr and continues with the existing install/model. A failed *install* (nothing to fall back to) is a hard error.
- All three update operations run under an explicit timeout with TERM-then-KILL escalation targeting the whole process group (not just the direct child PID) — verified empirically that a bare single-PID `kill` is not sufficient for a child that ignores SIGTERM.
- Suggested timeout budgets: ~20s grace period 3s for `opencode upgrade` and `pip install --upgrade`; ~90s grace period 5s for the model re-sync. These are tunable constants, not hardcoded assumptions to defend.
- The ModelScope-primary default flip is a separate, independently-revertible change from the update-checking behavior — its own task/commit.
- No new opt-out flag/env var for disabling update-checking entirely.
- Accepted risk (not mitigated by this plan): a successful `pip install --upgrade mlx-lm` is not proof `mlx_lm.server` still works; no rollback-on-regression is implemented.
- `demo/openCodeLocal` is not touched. The `modelscope` pip package installer step (lines 110-113, the download-fallback dependency) keeps its existing "install only if missing" behavior, untouched.

---

### Task 1: Portable timeout-with-escalation helper

**Files:**
- Create: `demo/lib/timeout.sh`
- Test: `demo/lib/test_timeout.sh`

**Interfaces:**
- Produces: `run_with_timeout <timeout_s> <grace_s> [--] <cmd> [args...]` — runs `<cmd>` with its args. If it exits on its own before `<timeout_s>` elapses, returns its real exit code. If it's still running at `<timeout_s>`, sends SIGTERM to its whole process group, waits up to `<grace_s>` more, then SIGKILL if still alive, and returns `124`. Requires the caller's shell to be bash (uses `set -m` internally).

- [ ] **Step 1: Write the failing test**

Create `demo/lib/test_timeout.sh`:

```bash
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash demo/lib/test_timeout.sh`
Expected: FAIL — `./timeout.sh` doesn't exist yet, so the `source` line errors out (`No such file or directory`) and the script exits before printing any PASS/FAIL lines.

- [ ] **Step 3: Write the implementation**

Create `demo/lib/timeout.sh`:

```bash
#!/usr/bin/env bash
# run_with_timeout <timeout_s> <grace_s> [--] <cmd> [args...]
#
# Runs <cmd> with a hard wall-clock budget. If it's still running after
# <timeout_s> seconds, sends SIGTERM to its whole process group; if it's
# still alive <grace_s> seconds after that, sends SIGKILL. Returns <cmd>'s
# real exit code if it finished on its own, or 124 if it had to be killed
# (matching GNU coreutils `timeout`'s convention).
#
# Requires bash job control (`set -m`) so the backgrounded command becomes
# its own process-group leader — a bare `kill` on the direct child PID is
# not enough: pip/python subprocesses can spawn children of their own, and
# a process blocked in a C-level blocking syscall can ignore a lone SIGTERM
# until that syscall returns.
run_with_timeout() {
  local timeout_s="$1" grace_s="$2"
  shift 2
  [[ "${1:-}" == "--" ]] && shift

  local killed_flag
  killed_flag=$(mktemp)
  rm -f "$killed_flag"

  set -m
  "$@" &
  local cmd_pid=$!
  set +m

  (
    sleep "$timeout_s"
    if kill -0 "$cmd_pid" 2>/dev/null; then
      : > "$killed_flag"
      kill -TERM "-$cmd_pid" 2>/dev/null
      sleep "$grace_s"
      kill -KILL "-$cmd_pid" 2>/dev/null
    fi
  ) &
  local watchdog_pid=$!

  local status=0
  wait "$cmd_pid" 2>/dev/null || status=$?

  kill "$watchdog_pid" 2>/dev/null
  wait "$watchdog_pid" 2>/dev/null

  if [[ -f "$killed_flag" ]]; then
    rm -f "$killed_flag"
    return 124
  fi
  return "$status"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash demo/lib/test_timeout.sh`
Expected:
```
PASS: fast command returns its own exit code
PASS: fast command does not wait for the timeout
PASS: TERM-responsive overrun returns 124
PASS: TERM-responsive overrun does not wait out the full sleep
PASS: SIGTERM-ignoring overrun still returns 124
PASS: SIGTERM-ignoring overrun is killed near timeout+grace, not the full sleep
PASS: grandchild process is also reaped
---
7 passed, 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add demo/lib/timeout.sh demo/lib/test_timeout.sh
git commit -m "feat: add run_with_timeout helper with process-group kill escalation"
```

---

### Task 2: `ensure_opencode_current`

**Files:**
- Create: `demo/lib/updates.sh`
- Create: `demo/lib/test_helpers.sh`
- Test: `demo/lib/test_updates_opencode.sh`

**Interfaces:**
- Consumes: `run_with_timeout` (Task 1, `demo/lib/timeout.sh`).
- Produces: `_install_opencode_via_curl` (private, no args) — runs the official curl-piped-to-bash installer; broken out on its own so tests can override it directly as a bash function rather than PATH-stubbing `curl`/`bash` (see Step 1 note). `ensure_opencode_current` (no args) — calls `_install_opencode_via_curl` if opencode isn't on PATH, or runs `opencode upgrade` (wrapped in a 20s/3s timeout) if it's already on PATH. Returns 0 if opencode is available afterwards either way; returns 1 only if it was never available and the install attempt failed. Emits its own progress/warning messages to stdout/stderr. Both defined in `demo/lib/updates.sh`, which sources `timeout.sh`.
- Produces (test helper, reused by Tasks 3 & 4): `demo/lib/test_helpers.sh` with `make_stub <bin_dir> <name> <exit_code> <log_file>` (writes an executable stub named `<name>` into `<bin_dir>` that appends its invocation args to `<log_file>` and exits with `<exit_code>`) and `check <desc> <expected> <actual>` (prints PASS/FAIL, tracks counts in global `pass`/`fail`).

- [ ] **Step 1: Write the failing test**

Create `demo/lib/test_helpers.sh`:

```bash
#!/usr/bin/env bash
# Shared helpers for demo/lib/test_*.sh scripts.

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

# make_stub <bin_dir> <name> <exit_code> <log_file>
# Writes an executable <bin_dir>/<name> that appends "<name> $*" to
# <log_file> (one call per line) and exits with <exit_code>.
make_stub() {
  local bin_dir="$1" name="$2" exit_code="$3" log_file="$4"
  cat > "$bin_dir/$name" <<EOF
#!/usr/bin/env bash
echo "$name \$*" >> "$log_file"
exit $exit_code
EOF
  chmod +x "$bin_dir/$name"
}

report() {
  echo "---"
  echo "$pass passed, $fail failed"
  [[ $fail -eq 0 ]]
}
```

Create `demo/lib/test_updates_opencode.sh`:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./updates.sh

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

report
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash demo/lib/test_updates_opencode.sh`
Expected: FAIL — `./updates.sh` doesn't exist yet, `source` errors out before any PASS/FAIL lines print.

- [ ] **Step 3: Write the implementation**

Create `demo/lib/updates.sh`:

```bash
#!/usr/bin/env bash
# Sourceable "ensure X is current" functions for demo/openCodeMlx.
# Every function prints its own progress/warning messages; callers only
# need to check the return code to decide whether to hard-error.

source "$(dirname "${BASH_SOURCE[0]}")/timeout.sh"

# _install_opencode_via_curl: runs the official installer. Broken out as its
# own function (rather than inlined) so tests can override it directly as a
# bash function instead of PATH-stubbing curl/bash — some sandboxed shells
# intercept those two names by identity regardless of PATH shadowing.
_install_opencode_via_curl() {
  curl -fsSL https://opencode.ai/install | bash
}

# ensure_opencode_current: installs opencode via the official installer if
# it's not on PATH, or upgrades it (best-effort, warn-on-failure) if it
# already is. Returns 0 if opencode is available afterwards, 1 only if it
# was never available and installing it failed.
ensure_opencode_current() {
  if ! command -v opencode >/dev/null 2>&1; then
    echo "opencode could not be found, installing..."
    _install_opencode_via_curl
    export PATH="$HOME/.opencode/bin:$PATH"
    if ! command -v opencode >/dev/null 2>&1; then
      echo "opencode installation failed, cannot continue" >&2
      return 1
    fi
    echo "opencode installed successfully"
    return 0
  fi

  echo "Checking for opencode updates..."
  if ! run_with_timeout 20 3 -- opencode upgrade; then
    echo "Warning: opencode upgrade failed or timed out, continuing with existing version." >&2
  fi
  return 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash demo/lib/test_updates_opencode.sh`
Expected:
```
PASS: not-installed + install produces a binary -> returns 0
PASS: not-installed + install produces no binary -> returns 1 (hard error)
PASS: already-installed + upgrade succeeds -> returns 0
PASS: already-installed + upgrade succeeds -> opencode upgrade was invoked
PASS: already-installed + upgrade fails -> still returns 0 (warn and continue)
---
5 passed, 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add demo/lib/updates.sh demo/lib/test_helpers.sh demo/lib/test_updates_opencode.sh
git commit -m "feat: add ensure_opencode_current (install-or-upgrade with warn-and-continue)"
```

---

### Task 3: `ensure_mlx_lm_current`

**Files:**
- Modify: `demo/lib/updates.sh` (append)
- Test: `demo/lib/test_updates_mlxlm.sh`

**Interfaces:**
- Consumes: `run_with_timeout` (Task 1). Reuses `demo/lib/test_helpers.sh` (Task 2).
- Produces: `ensure_mlx_lm_current` (no args) — assumes the caller has already activated the target venv. Installs `mlx-lm` (+ pinned `transformers`) if `mlx_lm.server --help` fails, or upgrades it (wrapped in a 20s/3s timeout, warn-on-failure) if it already works. Returns 0 if mlx-lm is available afterwards, 1 only if it was never available and the install attempt failed.

- [ ] **Step 1: Write the failing test**

Create `demo/lib/test_updates_mlxlm.sh`:

```bash
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash demo/lib/test_updates_mlxlm.sh`
Expected: FAIL — `ensure_mlx_lm_current: command not found` (function doesn't exist yet in `updates.sh`).

- [ ] **Step 3: Write the implementation**

Append to `demo/lib/updates.sh`:

```bash

# ensure_mlx_lm_current: installs mlx-lm (+ pinned transformers) into the
# already-activated venv if missing, or upgrades it (best-effort,
# warn-on-failure) if it's already present. Returns 0 if mlx-lm is
# available afterwards, 1 only if it was never available and the install
# attempt failed.
ensure_mlx_lm_current() {
  if ! mlx_lm.server --help >/dev/null 2>&1; then
    echo "mlx-lm not found in venv, installing..."
    # transformers>=5.13 breaks mlx-lm's AutoTokenizer.register call at
    # import time; pin below it.
    if ! run_with_timeout 20 3 -- pip install --upgrade pip mlx-lm "transformers>=5.7,<5.13"; then
      echo "Error: mlx-lm installation failed, cannot continue" >&2
      return 1
    fi
    return 0
  fi

  echo "Checking for mlx-lm updates..."
  if ! run_with_timeout 20 3 -- pip install --upgrade pip mlx-lm "transformers>=5.7,<5.13"; then
    echo "Warning: mlx-lm update check failed or timed out, continuing with existing installation." >&2
  fi
  return 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash demo/lib/test_updates_mlxlm.sh`
Expected:
```
PASS: not-installed + pip install succeeds -> returns 0
PASS: not-installed + pip install succeeds -> pip was invoked with --upgrade
PASS: not-installed + pip install fails -> returns 1 (hard error)
PASS: already-installed + pip update fails -> still returns 0 (warn and continue)
PASS: already-installed + pip update succeeds -> returns 0
PASS: already-installed -> pip is still invoked (update not skipped)
---
6 passed, 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add demo/lib/updates.sh demo/lib/test_updates_mlxlm.sh
git commit -m "feat: add ensure_mlx_lm_current (install-or-upgrade with warn-and-continue)"
```

---

### Task 4: `sync_model`

**Files:**
- Modify: `demo/lib/updates.sh` (append)
- Test: `demo/lib/test_updates_model.sh`

**Interfaces:**
- Consumes: `run_with_timeout` (Task 1). Reuses `demo/lib/test_helpers.sh` (Task 2).
- Produces:
  - `_download_via_huggingface <model_id> <local_dir>` (private) — shells out to `python3 -c "..."` using `huggingface_hub.snapshot_download`.
  - `_download_via_modelscope <model_id> <local_dir>` (private) — same, via `modelscope.snapshot_download`.
  - `_download_via <source: huggingface|modelscope> <model_id> <local_dir>` (private) — dispatches to one of the above, wrapped in a 90s/5s timeout.
  - `sync_model <model_id> <local_dir> <primary> <fallback>` — ensures `<local_dir>` holds a current copy of `<model_id>`, trying `<primary>` then `<fallback>` (each one of `huggingface`/`modelscope`). If `<local_dir>` doesn't exist or is empty (first run), both sources failing is a hard error (returns 1). If it already has content, both sources failing only warns and keeps the existing copy (returns 0).
  - Tests override `_download_via_huggingface`/`_download_via_modelscope` directly (by redefining the function after sourcing `updates.sh`) to simulate success/failure without touching the network.

- [ ] **Step 1: Write the failing test**

Create `demo/lib/test_updates_model.sh`:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./updates.sh

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Scenario A: dir missing, primary succeeds -> returns 0, dir gets created by the stub.
dir_a="$work/a"
_download_via_huggingface() { mkdir -p "$2"; touch "$2/weights.bin"; return 0; }
_download_via_modelscope() { return 1; }
sync_model "some/model" "$dir_a" huggingface modelscope >/dev/null 2>&1
check "not-present + primary succeeds -> returns 0" "0" "$?"
check "not-present + primary succeeds -> file was written" "1" "$([[ -f "$dir_a/weights.bin" ]] && echo 1 || echo 0)"

# Scenario B: dir missing, primary fails, fallback succeeds -> returns 0.
dir_b="$work/b"
_download_via_huggingface() { return 1; }
_download_via_modelscope() { mkdir -p "$2"; touch "$2/weights.bin"; return 0; }
sync_model "some/model" "$dir_b" huggingface modelscope >/dev/null 2>&1
check "not-present + primary fails + fallback succeeds -> returns 0" "0" "$?"

# Scenario C: dir missing, both fail -> hard error (returns 1).
dir_c="$work/c"
_download_via_huggingface() { return 1; }
_download_via_modelscope() { return 1; }
sync_model "some/model" "$dir_c" huggingface modelscope >/dev/null 2>&1
check "not-present + both fail -> returns 1 (hard error)" "1" "$?"

# Scenario D: dir already present, primary succeeds -> returns 0, primary was actually invoked (not skipped).
dir_d="$work/d"; mkdir -p "$dir_d"; touch "$dir_d/weights.bin"
calls_d="$work/d.calls"; : > "$calls_d"
_download_via_huggingface() { echo "hf-called" >> "$calls_d"; return 0; }
_download_via_modelscope() { echo "ms-called" >> "$calls_d"; return 1; }
sync_model "some/model" "$dir_d" huggingface modelscope >/dev/null 2>&1
check "already-present + primary succeeds -> returns 0" "0" "$?"
check "already-present -> re-sync is attempted, not skipped" "1" "$(grep -c hf-called "$calls_d")"

# Scenario E: dir already present, both sources fail -> still returns 0 (warn+continue, keep existing copy).
dir_e="$work/e"; mkdir -p "$dir_e"; touch "$dir_e/weights.bin"
_download_via_huggingface() { return 1; }
_download_via_modelscope() { return 1; }
sync_model "some/model" "$dir_e" huggingface modelscope >/dev/null 2>&1
check "already-present + both fail -> returns 0 (warn, keep existing copy)" "0" "$?"
check "already-present + both fail -> existing file untouched" "1" "$([[ -f "$dir_e/weights.bin" ]] && echo 1 || echo 0)"

report
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash demo/lib/test_updates_model.sh`
Expected: FAIL — `sync_model: command not found` (function doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Append to `demo/lib/updates.sh`:

```bash

# _download_via_huggingface <model_id> <local_dir>
_download_via_huggingface() {
  local model_id="$1" local_dir="$2"
  echo "Downloading $model_id from Hugging Face Hub to $local_dir..."
  python3 -c "
import sys
try:
    from huggingface_hub import snapshot_download
    snapshot_download(repo_id='$model_id', local_dir='$local_dir')
except Exception as e:
    print(f'Hugging Face download failed: {e}', file=sys.stderr)
    sys.exit(1)
"
}

# _download_via_modelscope <model_id> <local_dir>
_download_via_modelscope() {
  local model_id="$1" local_dir="$2"
  echo "Downloading $model_id from ModelScope to $local_dir..."
  python3 -c "
import sys
try:
    from modelscope import snapshot_download
    snapshot_download(model_id='$model_id', local_dir='$local_dir')
except Exception as e:
    print(f'ModelScope download failed: {e}', file=sys.stderr)
    sys.exit(1)
"
}

# _download_via <huggingface|modelscope> <model_id> <local_dir>
_download_via() {
  local source="$1" model_id="$2" local_dir="$3"
  case "$source" in
    modelscope) run_with_timeout 90 5 -- _download_via_modelscope "$model_id" "$local_dir" ;;
    huggingface) run_with_timeout 90 5 -- _download_via_huggingface "$model_id" "$local_dir" ;;
    *) echo "Unknown download source: $source" >&2; return 1 ;;
  esac
}

# sync_model <model_id> <local_dir> <primary> <fallback>
# Ensures <local_dir> holds a current copy of <model_id>, trying <primary>
# then <fallback> (each huggingface|modelscope). If <local_dir> doesn't
# exist/is empty yet (first run), both sources failing is a hard error. If
# it already has content, both sources failing only warns and keeps the
# existing copy.
sync_model() {
  local model_id="$1" local_dir="$2" primary="$3" fallback="$4"
  local already_present=0
  if [[ -d "$local_dir" && -n "$(ls -A "$local_dir" 2>/dev/null)" ]]; then
    already_present=1
  fi

  if [[ $already_present -eq 0 ]]; then
    echo "Model $model_id not found locally, downloading..."
  else
    echo "Checking $model_id for updates..."
  fi

  if _download_via "$primary" "$model_id" "$local_dir"; then
    return 0
  fi

  echo "Download via $primary failed, trying $fallback..." >&2
  if [[ $already_present -eq 0 ]]; then
    rm -rf "$local_dir"
  fi

  if _download_via "$fallback" "$model_id" "$local_dir"; then
    return 0
  fi

  if [[ $already_present -eq 0 ]]; then
    echo "Error: failed to download $model_id from both Hugging Face and ModelScope." >&2
    return 1
  fi

  echo "Warning: update check failed for both sources, continuing with existing local copy of $model_id." >&2
  return 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash demo/lib/test_updates_model.sh`
Expected:
```
PASS: not-present + primary succeeds -> returns 0
PASS: not-present + primary succeeds -> file was written
PASS: not-present + primary fails + fallback succeeds -> returns 0
PASS: not-present + both fail -> returns 1 (hard error)
PASS: already-present + primary succeeds -> returns 0
PASS: already-present -> re-sync is attempted, not skipped
PASS: already-present + both fail -> returns 0 (warn, keep existing copy)
PASS: already-present + both fail -> existing file untouched
---
8 passed, 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add demo/lib/updates.sh demo/lib/test_updates_model.sh
git commit -m "feat: add sync_model (re-syncs an existing model, not just first-download)"
```

---

### Task 5: Wire the new functions into `demo/openCodeMlx`

**Files:**
- Modify: `demo/openCodeMlx`

**Interfaces:**
- Consumes: `ensure_opencode_current` (Task 2), `ensure_mlx_lm_current` (Task 3), `sync_model` (Task 4) — all from `demo/lib/updates.sh`.

This task replaces the three old one-shot "install if missing" blocks with calls to the new functions, and deletes the now-redundant inline `download_model` function. It preserves the *existing* `MLXLM_USE_MODELSCOPE` default (huggingface-primary) — the default flip is Task 6, kept separate and independently revertible per the spec.

- [ ] **Step 1: Source the new library**

In `demo/openCodeMlx`, right after the arm64 check (currently lines 27-30) and before the opencode PATH setup, add:

```bash
source "$(dirname "${BASH_SOURCE[0]}")/lib/updates.sh"
```

- [ ] **Step 2: Replace the opencode install block**

Replace (currently lines 35-44):

```bash
if ! command -v opencode >/dev/null 2>&1; then
  echo "opencode could not be found, installing..."
  curl -fsSL https://opencode.ai/install | bash
  export PATH="$HOME/.opencode/bin:$PATH"
  if ! command -v opencode >/dev/null 2>&1; then
    echo "opencode installation failed, cannot continue"
    exit 1
  fi
  echo "opencode installed successfully"
fi
```

with:

```bash
if ! ensure_opencode_current; then
  exit 1
fi
```

- [ ] **Step 3: Replace the mlx-lm install block**

Replace (currently lines 96-101):

```bash
if ! mlx_lm.server --help >/dev/null 2>&1; then
  echo "mlx-lm not found in venv, installing..."
  # transformers>=5.13 breaks mlx-lm's AutoTokenizer.register call at import time
  # (AttributeError: 'str' object has no attribute '__module__'); pin below it.
  pip install --upgrade pip mlx-lm "transformers>=5.7,<5.13"
fi
```

with:

```bash
if ! ensure_mlx_lm_current; then
  exit 1
fi
```

- [ ] **Step 4: Remove the old `download_model` function**

Delete the entire `download_model()` function definition (currently lines 131-158) — its logic now lives in `demo/lib/updates.sh` as `_download_via_huggingface`/`_download_via_modelscope`/`_download_via`.

- [ ] **Step 5: Replace the model download-guard block**

Replace (currently lines 160-174):

```bash
if [[ ! -d "$LOCAL_MODEL_DIR" || -z "$(ls -A "$LOCAL_MODEL_DIR" 2>/dev/null)" ]]; then
  case "${MLXLM_USE_MODELSCOPE:-False}" in
    [Tt][Rr][Uu][Ee]) PRIMARY=modelscope; FALLBACK=huggingface ;;
    *) PRIMARY=huggingface; FALLBACK=modelscope ;;
  esac

  if ! download_model "$PRIMARY"; then
    echo "Download via $PRIMARY failed, trying $FALLBACK..."
    rm -rf "$LOCAL_MODEL_DIR"
    if ! download_model "$FALLBACK"; then
      echo "Error: failed to download $MLX_MODEL from both Hugging Face and ModelScope."
      exit 1
    fi
  fi
fi
```

with:

```bash
case "${MLXLM_USE_MODELSCOPE:-False}" in
  [Tt][Rr][Uu][Ee]) PRIMARY=modelscope; FALLBACK=huggingface ;;
  *) PRIMARY=huggingface; FALLBACK=modelscope ;;
esac

if ! sync_model "$MLX_MODEL" "$LOCAL_MODEL_DIR" "$PRIMARY" "$FALLBACK"; then
  exit 1
fi
```

- [ ] **Step 6: Verify the script still parses and is wired correctly**

Running the full real script here would cascade into real `pip`/venv/GPU/sudo/model-download side effects (only `opencode` would be stubbed), so this step is a syntax check plus static structural assertions instead of an end-to-end execution — Tasks 1-4's test suites already cover each function's runtime behavior in isolation.

Run: `bash -n demo/openCodeMlx`
Expected: no output, exit code 0.

Run these structural checks:

```bash
# The old inline function must be gone...
if grep -q '^download_model()' demo/openCodeMlx; then
  echo "FAIL: old download_model() function still present"
else
  echo "PASS: old download_model() function removed"
fi

# ...and each new call site must be present exactly once.
for fn in ensure_opencode_current ensure_mlx_lm_current sync_model; do
  count=$(grep -c "^\s*if ! ${fn}\b" demo/openCodeMlx)
  if [[ "$count" -eq 1 ]]; then
    echo "PASS: $fn is called exactly once"
  else
    echo "FAIL: $fn call count is $count, expected 1"
  fi
done

# The lib must be sourced before any of the new functions are called.
source_line=$(grep -n 'source.*lib/updates.sh' demo/openCodeMlx | head -1 | cut -d: -f1)
first_call_line=$(grep -n 'ensure_opencode_current' demo/openCodeMlx | head -1 | cut -d: -f1)
if [[ -n "$source_line" && -n "$first_call_line" && "$source_line" -lt "$first_call_line" ]]; then
  echo "PASS: lib/updates.sh is sourced before first use"
else
  echo "FAIL: lib/updates.sh source line ($source_line) is not before first use ($first_call_line)"
fi
```

Expected:
```
PASS: old download_model() function removed
PASS: ensure_opencode_current is called exactly once
PASS: ensure_mlx_lm_current is called exactly once
PASS: sync_model is called exactly once
PASS: lib/updates.sh is sourced before first use
```

- [ ] **Step 7: Commit**

```bash
git add demo/openCodeMlx
git commit -m "feat: wire ensure_opencode_current/ensure_mlx_lm_current/sync_model into openCodeMlx"
```

---

### Task 6: Flip default model download order to ModelScope-primary

**Files:**
- Modify: `demo/openCodeMlx`

This is a separate, independently-revertible decision (per spec) from the update-checking behavior — its own commit so it can be reverted on its own if ModelScope turns out to lag HF for `mlx-community` quantizations.

- [ ] **Step 1: Flip the default**

In `demo/openCodeMlx`, change the case statement added in Task 5, Step 5:

```bash
case "${MLXLM_USE_MODELSCOPE:-False}" in
  [Tt][Rr][Uu][Ee]) PRIMARY=modelscope; FALLBACK=huggingface ;;
  *) PRIMARY=huggingface; FALLBACK=modelscope ;;
esac
```

to:

```bash
# Defaults to ModelScope-first: HF is blocked in this author's environment,
# and under always-on update checks a blocked primary now costs latency on
# every launch, not just the first. Set MLXLM_USE_MODELSCOPE=false on a
# machine where HF is reachable and preferred.
case "${MLXLM_USE_MODELSCOPE:-True}" in
  [Ff][Aa][Ll][Ss][Ee]) PRIMARY=huggingface; FALLBACK=modelscope ;;
  *) PRIMARY=modelscope; FALLBACK=huggingface ;;
esac
```

- [ ] **Step 2: Verify the script still parses**

Run: `bash -n demo/openCodeMlx`
Expected: no output, exit code 0.

- [ ] **Step 3: Commit**

```bash
git add demo/openCodeMlx
git commit -m "feat: default openCodeMlx model download order to ModelScope-primary

Separate from the update-checking work: HF is blocked in this author's
environment, and every-launch update checks would otherwise retry a
doomed HF attempt on every run. MLXLM_USE_MODELSCOPE=false restores
HF-primary on a machine where HF is reachable and preferred."
```

---

## Final verification (run once all tasks are complete)

```bash
bash -n demo/openCodeMlx
bash demo/lib/test_timeout.sh
bash demo/lib/test_updates_opencode.sh
bash demo/lib/test_updates_mlxlm.sh
bash demo/lib/test_updates_model.sh
```

Expected: all four test scripts print `0 failed`, and the syntax check is silent.
