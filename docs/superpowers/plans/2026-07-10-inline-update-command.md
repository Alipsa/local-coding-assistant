# Inline Update Library + Explicit `update` Subcommand Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `demo/openCodeMlx` a single self-contained file (no `demo/lib/`, no sourcing, no symlink-resolution bugs), and make update-checking an explicit `openCodeMlx update` subcommand instead of something that runs on every normal launch.

**Architecture:** Wrap the script's existing top-to-bottom launch flow in a `main()` function, guarded so the file can be `source`d (for tests) without running anything. Move `demo/lib/timeout.sh` and `demo/lib/updates.sh`'s function bodies directly into `demo/openCodeMlx` as top-level function definitions above `main()`. Give `ensure_opencode_current`, `ensure_mlx_lm_current`, and `sync_model` a mode argument (`install-only` vs `update`) so the same functions serve both a fast normal launch (install-if-missing only) and the new `update` subcommand (install-or-upgrade, today's shipped behavior). Delete `demo/lib/` entirely; its four test files move to `demo/` and source `openCodeMlx` directly instead.

**Tech Stack:** bash (macOS/BSD tools only, same as before).

## Global Constraints

- `demo/openCodeMlx` must be safe to `source` for testing: sourcing it must produce no output and no side effects (no arm64 check, no installs, no network calls) — only function definitions. This is achieved via `if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then main "$@"; fi` at the end of the file.
- Normal launch (`openCodeMlx` with no subcommand, or with args destined for `opencode` itself) must call `ensure_opencode_current`/`ensure_mlx_lm_current`/`sync_model` in `install-only` mode: install if missing, otherwise use as-is with zero extra network calls or upgrade attempts.
- `openCodeMlx update` must call the same three functions in `update` mode (today's shipped install-or-upgrade, warn-and-continue-on-failure behavior, unchanged), then print a completion message and exit — without running the GPU/RAM `sysctl` tuning block, the port-availability check, or starting `mlx_lm.server`/launching `opencode`.
- Any first argument other than the literal string `update` (including no argument) must behave exactly as today: full `install-only`-mode launch flow, then `opencode --pure "$@"` with the original argument list untouched.
- The "not present" install branch of all three functions is unchanged from the already-reviewed implementation (including the `_install_opencode_via_curl || true` `set -e` safety fix) — only the "already present" branch gates on mode.
- `demo/lib/timeout.sh`, `demo/lib/updates.sh`, and their four test files are deleted once their contents/tests have moved into `demo/openCodeMlx` and `demo/test_*.sh`.
- No new flags beyond the single `update` subcommand.

---

### Task 1: Wrap the launch flow in `main()` behind a sourcing guard

**Files:**
- Modify: `demo/openCodeMlx`
- Test: `demo/test_main_guard.sh` (new)

**Interfaces:**
- Produces: a `main()` function containing everything the script does today (arm64 check through `opencode --pure "$@"`), invoked only via a trailing guard when the file is executed, not sourced. No functions are relocated into this file yet — `demo/lib/updates.sh` is still sourced from inside `main()`, unchanged. No mode/update-subcommand behavior yet either. This task is a pure, behavior-preserving mechanical refactor.

This task is a big move, not a rewrite — every line inside the new `main()` is copied verbatim from the current file, just indented one level deeper.

- [ ] **Step 1: Write the test**

A safety note before writing this: this test must never actually run the *old*, unwrapped script's side-effecting flow (it does real installs, a `sudo sysctl` prompt, and eventually launches an interactive TUI that would hang waiting for a terminal) — so unlike the usual TDD dance, do not attempt to run this test against the pre-refactor file to "watch it fail." It's self-evident from reading the current file that sourcing it today runs the arm64 check and beyond immediately (there's no guard at all yet) — write the test, implement the fix, then run the test only against the *new* guarded file.

Create `demo/test_main_guard.sh`:

```bash
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
```

- [ ] **Step 2: Run the test to confirm it fails against the current (unguarded) file**

Run: `bash demo/test_main_guard.sh`
Expected: FAIL on the first two checks (sourcing runs the real arm64 check and beyond, producing output and/or hanging) — if this command doesn't return within a few seconds, it means the old script's real flow is running (e.g. waiting on a `sudo` password prompt or a TUI); press Ctrl-C and proceed straight to implementing the fix rather than letting it run further. Do not let this reach `opencode --pure`.

- [ ] **Step 3: Implement `main()` + guard**

Modify `demo/openCodeMlx`. Move `set -e` and everything from the arm64 check through the final `opencode --pure "$@"` line into a new `main()` function, keeping the header comment and the four `MLX_MODEL`/`OS_RESERVE_GB`/`MLX_CONTEXT_LIMIT`/`MLX_OUTPUT_LIMIT` lines at top level (harmless declarations, no side effects), and add the guard at the end. The full new file:

```bash
#!/usr/bin/env bash
############################################################
# Launch script to run OpenCode cli using mlx-lm to serve   #
# a local model directly on Apple Silicon (no Ollama).      #
# Author: Per Nyfelt                                        #
############################################################

# --- User-tunable settings ---
# Model to run - comment one out, uncomment the other to switch.
# MLX_MODEL="${MLX_MODEL:-mlx-community/Qwen3-Coder-30B-A3B-Instruct-8bit}"
MLX_MODEL="${MLX_MODEL:-mlx-community/Qwen3-Coder-Next-4bit}"

# How much RAM (GB) to leave free for the OS/other apps; the rest is made
# available to the GPU (iogpu.wired_limit_mb) for model weights + KV cache.
OS_RESERVE_GB=10

# Tell opencode the model's real context/output token limits so its auto-compaction
# (compaction.auto below) can summarize old conversation before a request gets big
# enough to exceed available GPU memory. Custom/local providers don't get this from
# models.dev automatically, so it must be declared here. Qwen3-Coder-Next has a 256k
# native context; default to half of that to leave GPU headroom (a single oversized
# message can still exceed this - compaction only trims prior turns, not the current one).
MLX_CONTEXT_LIMIT="${MLX_CONTEXT_LIMIT:-128000}"
MLX_OUTPUT_LIMIT="${MLX_OUTPUT_LIMIT:-16384}"

main() {
  set -e

  if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    echo "MLX requires an Apple Silicon Mac (macOS + arm64), cannot continue"
    exit 1
  fi

  # ${BASH_SOURCE[0]} does not follow symlinks, so a naive dirname breaks when
  # this script is invoked through a symlink (e.g. installed into ~/.local/bin
  # pointing back at the repo checkout, the usual way these demo scripts are
  # run) - dirname would resolve to the symlink's own directory instead of
  # where lib/ actually lives. Resolve the real path first.
  resolve_script_dir() {
    local source_path="$1" dir
    while [[ -L "$source_path" ]]; do
      dir="$(cd -P "$(dirname "$source_path")" >/dev/null 2>&1 && pwd)"
      source_path="$(readlink "$source_path")"
      [[ "$source_path" != /* ]] && source_path="$dir/$source_path"
    done
    cd -P "$(dirname "$source_path")" >/dev/null 2>&1 && pwd
  }

  source "$(resolve_script_dir "${BASH_SOURCE[0]}")/lib/updates.sh"

  # Ensure opencode bin dir is on PATH (installer adds it to .zshrc but current shell may not have it)
  [[ -d "$HOME/.opencode/bin" ]] && export PATH="$HOME/.opencode/bin:$PATH"

  if ! ensure_opencode_current; then
    exit 1
  fi

  # Set appropriate GPU memory tuning based on the installed memory size
  RAM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo "0")
  RAM_GB=$((RAM_BYTES / 1073741824))

  # Subtract OS_RESERVE_GB from available RAM for GPU limit (rounded down to nearest 1024MB)
  GPU_WIRED_LIMIT=$(( (RAM_GB - OS_RESERVE_GB) * 1024 ))

  # Ensure minimum 24GB (24576 MB) limit for smaller systems
  if [[ $GPU_WIRED_LIMIT -lt 24576 ]]; then
    echo "Error: ${RAM_GB}GB RAM detected. Minimum 32GB required."
    exit 1
  fi

  echo "Detected ${RAM_GB}GB RAM -> GPU: $((GPU_WIRED_LIMIT/1024))GB"

  if [[ $RAM_GB -lt 64 ]]; then
    echo "Warning: $MLX_MODEL may need ~45GB for weights + KV cache and may not fit"
    echo "in ${RAM_GB}GB RAM. Override with a smaller or lower-bit model via the"
    echo "MLX_MODEL environment variable if you hit an OOM."
  fi

  # The mac default is quite conservative and limits the GPU memory too much
  # increasing it greatly improved local llm performance
  current_limit=$(sysctl -n iogpu.wired_limit_mb 2>/dev/null || echo "unknown")
  if [[ "$current_limit" != "$GPU_WIRED_LIMIT" ]]; then
    echo "GPU limit (iogpu.wired_limit_mb) is $current_limit, setting to $GPU_WIRED_LIMIT (requires sudo)..."
    sudo sysctl iogpu.wired_limit_mb=$GPU_WIRED_LIMIT
  fi

  # Set up a dedicated venv for mlx-lm so it doesn't pollute system/other project Python envs
  MLX_DIR="$HOME/.opencode_mlx"
  VENV_PATH="$MLX_DIR/venv"
  mkdir -p "$MLX_DIR"

  # We download models into our own local_dir below rather than the default HF cache, so
  # ~/.cache/huggingface/hub is never created. mlx_lm.server's GET /v1/models handler still
  # unconditionally scans it (huggingface_hub.scan_cache_dir), and raises CacheNotFound in an
  # uncaught exception when it's missing - which leaves that one HTTP connection hanging with
  # no response, ever, since the handler never gets to write one. opencode polls /v1/models as
  # a connectivity check, so hitting this mid-session silently freezes it (no error, no retry
  # message) instead of failing cleanly. An empty dir is enough to satisfy the scan.
  mkdir -p "$HOME/.cache/huggingface/hub"

  if [[ ! -d "$VENV_PATH" ]]; then
    echo "Creating virtual environment for mlx-lm..."
    python3 -m venv "$VENV_PATH"
  fi

  source "$VENV_PATH/bin/activate"

  if ! ensure_mlx_lm_current; then
    exit 1
  fi

  # mlx-lm's own --model download path always goes through huggingface_hub, with no
  # ModelScope support via its own downloader in this flow, so a huggingface.co
  # reachability check isn't enough (the homepage can respond while large model-weight
  # transfers still get reset). Instead we download the model ourselves ahead of time,
  # trying Hugging Face first and falling back to ModelScope on failure (or vice versa
  # if MLXLM_USE_MODELSCOPE is already set true), then point mlx_lm.server at the
  # resulting local directory so it never touches the network on its own.
  if ! python3 -c "import modelscope" >/dev/null 2>&1; then
    echo "Installing modelscope package (used as a model download fallback)..."
    pip install modelscope
  fi

  # 8080 is commonly held by other local dev tooling (e.g. Rancher Desktop's Lima VM
  # SSH tunnel binds it permanently), which uvicorn only discovers *after* finishing
  # its lifespan startup (loading the whole model) and only then trying to bind the
  # socket - so a collision wastes the full load time before failing. Default to a
  # less commonly used port and fail fast below if it's still taken.
  MLX_PORT="${MLX_PORT:-8090}"

  if lsof -i ":$MLX_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Error: port $MLX_PORT is already in use by another process:"
    lsof -i ":$MLX_PORT" -sTCP:LISTEN
    echo "Set MLX_PORT to a free port and try again."
    exit 1
  fi

  LOCAL_MODEL_DIR="$MLX_DIR/models/$(echo "$MLX_MODEL" | tr '/' '--')"

  # Defaults to ModelScope-first: HF is blocked in this author's environment,
  # and under always-on update checks a blocked primary now costs latency on
  # every launch, not just the first. Set MLXLM_USE_MODELSCOPE=false on a
  # machine where HF is reachable and preferred.
  case "${MLXLM_USE_MODELSCOPE:-True}" in
    [Ff][Aa][Ll][Ss][Ee]) PRIMARY=huggingface; FALLBACK=modelscope ;;
    *) PRIMARY=modelscope; FALLBACK=huggingface ;;
  esac

  if ! sync_model "$MLX_MODEL" "$LOCAL_MODEL_DIR" "$PRIMARY" "$FALLBACK"; then
    exit 1
  fi

  # Truncate once per script run; start_mlx_server appends so a crash's trace survives a restart.
  : > "$MLX_DIR/server.log"

  diagnose_server_crash() {
    if grep -qE "Insufficient Memory|kIOGPUCommandBufferCallbackErrorOutOfMemory|OutOfMemory" "$MLX_DIR/server.log" 2>/dev/null; then
      cat >&2 <<EOF

mlx_lm.server ran out of GPU memory (Metal OOM) and crashed. This tends to
happen on large prompts (e.g. reviewing a big PR diff) that grow the KV
cache past the GPU memory budget this script configured
(iogpu.wired_limit_mb = ${GPU_WIRED_LIMIT}). Options:
  - Use a smaller/lower-bit model: MLX_MODEL=... $0
  - Lower MLX_CONTEXT_LIMIT so opencode compacts history sooner
  - Give the GPU more headroom by lowering OS_RESERVE_GB in this script
    (leaves less for the OS/other apps - test carefully)
  - Avoid feeding it very large diffs/context in a single turn
EOF
    else
      echo "" >&2
      echo "mlx_lm.server (pid $SERVER_PID) crashed unexpectedly." >&2
    fi
    echo "Last log lines:" >&2
    tail -n 40 "$MLX_DIR/server.log" >&2
  }

  start_mlx_server() {
    echo "Starting mlx_lm.server with model $MLX_MODEL on port $MLX_PORT..."
    mlx_lm.server \
      --model "$LOCAL_MODEL_DIR" \
      --host 127.0.0.1 \
      --port "$MLX_PORT" \
      >> "$MLX_DIR/server.log" 2>&1 &
    SERVER_PID=$!

    echo "Waiting for mlx_lm.server to come up (first run may download the model, this can take several minutes)..."
    local ready=0
    for _ in $(seq 1 180); do
      if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        diagnose_server_crash
        return 1
      fi
      if curl -s -o /dev/null "http://127.0.0.1:$MLX_PORT/v1/models"; then
        ready=1
        break
      fi
      sleep 1
    done

    if [[ $ready -ne 1 ]]; then
      echo "Timed out waiting for mlx_lm.server to start, last log lines:" >&2
      tail -n 40 "$MLX_DIR/server.log" >&2
      return 1
    fi

    echo "mlx_lm.server is up."
    return 0
  }

  cleanup() {
    if kill -0 "${SERVER_PID:-}" 2>/dev/null; then
      echo "Stopping mlx_lm.server (pid $SERVER_PID)..."
      kill "$SERVER_PID" 2>/dev/null
      wait "$SERVER_PID" 2>/dev/null
    fi
  }
  trap cleanup EXIT INT TERM

  if ! start_mlx_server; then
    exit 1
  fi

  LOCAL_MODEL_PROMPT="You are running on a local LLM with limited capacity. Follow these rules strictly:
## No subagents
Do NOT spawn sub-agents or delegate to other agents. Work directly in the main conversation using read, edit, write, and bash tools. Subagents will loop and waste time on this model.
## Small steps only
When asked to implement a plan or feature:
1. Read the plan file first
2. Break it into individual file-level tasks (one file per step)
3. Work through them one at a time
4. Each step should be: read the relevant file, make the change, verify it compiles/passes
## Be concrete
- Never explore broadly. Ask the user which files to look at if unsure.
- Never churn. If you do not know what to do next, ask the user immediately.
- Never spend more than 5 tool calls exploring before starting to write code.
## Keep responses extremely short
- NEVER exceed 2000 tokens in a single response. If more work is needed, use multiple tool calls instead of writing long text.
- Maximum 2 sentences of explanation between tool calls
- No summaries, no restating the plan, no listing what you will do
- Do NOT output the entire file content when editing - use the edit tool with only the changed section
- Do NOT think out loud or explain your reasoning. Just act.
## When stuck
If you find yourself making more than 8 tool calls without producing a code change, STOP and tell the user what is blocking you. Do not keep searching."

  # Build config inline via OPENCODE_CONFIG_CONTENT
  # This avoids writing config files into the project directory
  export OPENCODE_CONFIG_CONTENT=$(cat <<EOJSON
{
  "model": "mlx/${LOCAL_MODEL_DIR}",
  "provider": {
    "mlx": {
      "options": {
        "baseURL": "http://127.0.0.1:${MLX_PORT}/v1"
      },
      "models": {
        "${LOCAL_MODEL_DIR}": {
          "tool_call": true,
          "limit": {
            "context": ${MLX_CONTEXT_LIMIT},
            "output": ${MLX_OUTPUT_LIMIT}
          }
        }
      }
    }
  },
  "agent": {
    "local": {
      "description": "Local LLM agent with constrained behaviour",
      "prompt": $(printf '%s' "$LOCAL_MODEL_PROMPT" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))'),
      "tools": {
        "read": true,
        "write": true,
        "edit": true,
        "bash": true
      }
    }
  },
  "default_agent": "local",
  "snapshot": true,
  "autoupdate": false,
  "compaction": {
    "auto": true
  }
}
EOJSON
)

  export OPENCODE_DISABLE_AUTOUPDATE=1

  # --pure: opencode tries to auto-install an @opencode-ai/plugin package on startup, which
  # 403s against a corporate npm registry. Harmless/fast in testing so far, but we don't
  # configure any plugins, so disabling them costs nothing.
  opencode --pure "$@"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `bash -n demo/openCodeMlx && bash demo/test_main_guard.sh`
Expected:
```
PASS: sourcing produces no output
PASS: sourcing exits 0
PASS: main is defined as a function after sourcing
PASS: guard invokes main "$@"
---
4 passed, 0 failed
```

- [ ] **Step 5: Commit**

```bash
git add demo/openCodeMlx demo/test_main_guard.sh
git commit -m "refactor: wrap openCodeMlx's launch flow in main() behind a sourcing guard"
```

---

### Task 2: Relocate `demo/lib/` function bodies into `demo/openCodeMlx`; delete `demo/lib/`

**Files:**
- Modify: `demo/openCodeMlx`
- Create: `demo/test_helpers.sh`, `demo/test_timeout.sh`, `demo/test_updates_opencode.sh`, `demo/test_updates_mlxlm.sh`, `demo/test_updates_model.sh` (moved from `demo/lib/`, contents unchanged except the `source` lines)
- Delete: `demo/lib/timeout.sh`, `demo/lib/updates.sh`, `demo/lib/test_helpers.sh`, `demo/lib/test_timeout.sh`, `demo/lib/test_updates_opencode.sh`, `demo/lib/test_updates_mlxlm.sh`, `demo/lib/test_updates_model.sh`, and the now-empty `demo/lib/` directory

**Interfaces:**
- Produces (all now top-level functions in `demo/openCodeMlx`, defined above `main()`): `run_with_timeout <timeout_s> <grace_s> [--] <cmd> [args...]`, `_install_opencode_via_curl`, `ensure_opencode_current` (no mode param yet — added in Task 3), `_download_via_huggingface <model_id> <local_dir>`, `_download_via_modelscope <model_id> <local_dir>`, `_download_via <huggingface|modelscope> <model_id> <local_dir>`, `sync_model <model_id> <local_dir> <primary> <fallback>` (no mode param yet), `ensure_mlx_lm_current` (no mode param yet). Signatures are unchanged from the already-reviewed `demo/lib/updates.sh`/`demo/lib/timeout.sh` — this task only relocates them.

This is a pure move: no behavior changes, no mode parameter yet (that's Task 3). The test cycle here is "confirm the moved tests still pass unchanged" rather than "write a new failing test," since nothing new is being built.

- [ ] **Step 1: Move and retarget the five test files**

Create `demo/test_helpers.sh` with the exact contents of `demo/lib/test_helpers.sh` (unchanged, no `source` lines to retarget):

```bash
#!/usr/bin/env bash
# Shared helpers for demo/test_*.sh scripts.

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

Create `demo/test_timeout.sh` — identical to `demo/lib/test_timeout.sh` except line 5 changes from `source ./timeout.sh` to `source ./openCodeMlx`:

```bash
#!/usr/bin/env bash
# Tests for run_with_timeout. Run directly: bash demo/test_timeout.sh
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./openCodeMlx

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
pre_count=$(pgrep sleep 2>/dev/null | wc -l | tr -d ' ' || echo 0)
run_with_timeout 10 2 -- bash -c 'exit 0' >/dev/null 2>&1
# Give any leaked processes a moment to be sure they're not in a race condition
sleep 0.5
post_count=$(pgrep sleep 2>/dev/null | wc -l | tr -d ' ' || echo 0)
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
```

Create `demo/test_updates_opencode.sh` — identical to `demo/lib/test_updates_opencode.sh` except line 5 changes to `source ./openCodeMlx`, and inside the heredoc at (old) line 22, `source ./updates.sh` changes to `source ./openCodeMlx`:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

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
source ./openCodeMlx
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
```

Create `demo/test_updates_mlxlm.sh` — identical to `demo/lib/test_updates_mlxlm.sh` except line 5 changes to `source ./openCodeMlx`:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

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

Create `demo/test_updates_model.sh` — identical to `demo/lib/test_updates_model.sh` except line 5 changes to `source ./openCodeMlx`:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

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

- [ ] **Step 2: Run the moved tests against the *old* `demo/openCodeMlx` to confirm they fail correctly**

They should fail simply because `run_with_timeout`/`ensure_opencode_current`/etc. aren't defined in `demo/openCodeMlx` yet (functions still live only in `demo/lib/`).

Run: `bash demo/test_timeout.sh`
Expected: FAIL — `run_with_timeout: command not found` (or similar), since sourcing `demo/openCodeMlx` at this point only defines `main`, not the library functions.

- [ ] **Step 3: Relocate the function bodies into `demo/openCodeMlx`**

Add all of `demo/lib/timeout.sh`'s and `demo/lib/updates.sh`'s function bodies as top-level definitions in `demo/openCodeMlx`, placed between the `MLX_OUTPUT_LIMIT` line and `main() {`. Then remove the now-unneeded `resolve_script_dir` function and the `source ".../lib/updates.sh"` line from inside `main()` (the functions are now already in scope from the top-level definitions — nothing to source).

Insert into `demo/openCodeMlx`, right after `MLX_OUTPUT_LIMIT="${MLX_OUTPUT_LIMIT:-16384}"` and before `main() {`:

```bash

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

  set -m
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
  set +m

  local status=0
  wait "$cmd_pid" 2>/dev/null || status=$?

  kill -- "-$watchdog_pid" 2>/dev/null
  wait "$watchdog_pid" 2>/dev/null

  if [[ -f "$killed_flag" ]]; then
    rm -f "$killed_flag"
    return 124
  fi
  return "$status"
}

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
    _install_opencode_via_curl || true
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
  # A successful upgrade here is not proof mlx_lm.server still works: pip
  # exiting 0 only means the new package versions installed cleanly, not
  # that they're compatible with each other. A post-upgrade server crash
  # won't necessarily match diagnose_server_crash's known OOM signatures,
  # and there's no rollback-on-regression — this is an accepted risk of
  # always-upgrade behavior (see docs/superpowers/specs/2026-07-09-opencode-mlx-updates-design.md).
  if ! run_with_timeout 20 3 -- pip install --upgrade pip mlx-lm "transformers>=5.7,<5.13"; then
    echo "Warning: mlx-lm update check failed or timed out, continuing with existing installation." >&2
  fi
  return 0
}

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

Then, inside `main()`, replace:

```bash
  # ${BASH_SOURCE[0]} does not follow symlinks, so a naive dirname breaks when
  # this script is invoked through a symlink (e.g. installed into ~/.local/bin
  # pointing back at the repo checkout, the usual way these demo scripts are
  # run) - dirname would resolve to the symlink's own directory instead of
  # where lib/ actually lives. Resolve the real path first.
  resolve_script_dir() {
    local source_path="$1" dir
    while [[ -L "$source_path" ]]; do
      dir="$(cd -P "$(dirname "$source_path")" >/dev/null 2>&1 && pwd)"
      source_path="$(readlink "$source_path")"
      [[ "$source_path" != /* ]] && source_path="$dir/$source_path"
    done
    cd -P "$(dirname "$source_path")" >/dev/null 2>&1 && pwd
  }

  source "$(resolve_script_dir "${BASH_SOURCE[0]}")/lib/updates.sh"

  # Ensure opencode bin dir is on PATH (installer adds it to .zshrc but current shell may not have it)
```

with:

```bash
  # Ensure opencode bin dir is on PATH (installer adds it to .zshrc but current shell may not have it)
```

(i.e. delete the `resolve_script_dir` definition and the `source` line entirely — there's nothing left to source, which is the whole point of this task).

- [ ] **Step 4: Delete `demo/lib/`**

```bash
rm -rf demo/lib
```

- [ ] **Step 5: Run all five moved test files to confirm they pass**

Run: `bash -n demo/openCodeMlx && bash demo/test_timeout.sh && bash demo/test_updates_opencode.sh && bash demo/test_updates_mlxlm.sh && bash demo/test_updates_model.sh`

Expected: syntax check silent, then:
```
PASS: fast command returns its own exit code
PASS: fast command does not wait for the timeout
PASS: no lingering watchdog sleep on fast-exit path
PASS: TERM-responsive overrun returns 124
PASS: TERM-responsive overrun does not wait out the full sleep
PASS: SIGTERM-ignoring overrun still returns 124
PASS: SIGTERM-ignoring overrun is killed near timeout+grace, not the full sleep
PASS: grandchild process is also reaped
---
8 passed, 0 failed
PASS: not-installed + install produces a binary -> returns 0
PASS: not-installed + install produces no binary -> returns 1 (hard error)
PASS: already-installed + upgrade succeeds -> returns 0
PASS: already-installed + upgrade succeeds -> opencode upgrade was invoked
PASS: already-installed + upgrade fails -> still returns 0 (warn and continue)
PASS: install fails under set -e -> function returns 1 (not aborted by set -e)
---
6 passed, 0 failed
PASS: not-installed + pip install succeeds -> returns 0
PASS: not-installed + pip install succeeds -> pip was invoked with --upgrade
PASS: not-installed + pip install fails -> returns 1 (hard error)
PASS: already-installed + pip update fails -> still returns 0 (warn and continue)
PASS: already-installed + pip update succeeds -> returns 0
PASS: already-installed -> pip is still invoked (update not skipped)
---
6 passed, 0 failed
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

Also re-run `bash demo/test_main_guard.sh` (from Task 1) to confirm it's still green — sourcing must still be side-effect-free now that far more code lives at the top level of the file.

- [ ] **Step 6: Commit**

```bash
git add demo/openCodeMlx demo/test_helpers.sh demo/test_timeout.sh demo/test_updates_opencode.sh demo/test_updates_mlxlm.sh demo/test_updates_model.sh
git rm -r demo/lib
git commit -m "refactor: inline demo/lib/*.sh into openCodeMlx, delete demo/lib/

Eliminates the symlink-resolution problem at its root (there's no
companion file to locate anymore) instead of just patching around it."
```

---

### Task 3: Add `install-only` vs `update` mode; wire the `update` subcommand into `main()`

**Files:**
- Modify: `demo/openCodeMlx`, `demo/test_updates_opencode.sh`, `demo/test_updates_mlxlm.sh`, `demo/test_updates_model.sh`

**Interfaces:**
- Consumes: the functions from Task 2.
- Produces: `ensure_opencode_current <mode>`, `ensure_mlx_lm_current <mode>`, `sync_model <model_id> <local_dir> <primary> <fallback> <mode>` where `<mode>` is `install-only` or `update` (defaults to `update` if omitted). `main()` now dispatches on `$1 == "update"`.

- [ ] **Step 1: Write the new test scenarios (install-only mode)**

Add to `demo/test_updates_opencode.sh`, right after Scenario D (before Scenario E):

```bash

# Scenario F: opencode already on PATH, called in install-only mode -> upgrade
# must NOT be attempted (the stub is set to fail loudly if invoked, proving
# the skip is real rather than a coincidental success).
bin_f="$work/f"; mkdir -p "$bin_f"
log_f="$work/f.log"
make_stub "$bin_f" opencode 1 "$log_f"
(
  PATH="$bin_f:$PATH" ensure_opencode_current install-only >/dev/null 2>&1
)
check "already-installed + install-only mode -> returns 0" "0" "$?"
check "already-installed + install-only mode -> upgrade was NOT invoked" "0" "$(grep -c '^opencode upgrade$' "$log_f")"
```

Add to `demo/test_updates_mlxlm.sh`, right after Scenario D (before `report`):

```bash

# Scenario E: mlx_lm.server present, called in install-only mode -> pip
# upgrade must NOT be attempted.
bin_e="$work/e"; mkdir -p "$bin_e"
log_e="$work/e.log"
make_stub "$bin_e" mlx_lm.server 0 "$log_e"
make_stub "$bin_e" pip 1 "$log_e"
(
  PATH="$bin_e:$REAL_PATH" ensure_mlx_lm_current install-only >/dev/null 2>&1
)
check "already-installed + install-only mode -> returns 0" "0" "$?"
check "already-installed + install-only mode -> pip was NOT invoked" "0" "$(grep -c '^pip ' "$log_e")"
```

Add to `demo/test_updates_model.sh`, right after Scenario E (before `report`):

```bash

# Scenario F: dir already present, called in install-only mode -> no download
# attempt at all (primary override fails loudly if invoked).
dir_f="$work/f"; mkdir -p "$dir_f"; touch "$dir_f/weights.bin"
_download_via_huggingface() { echo "SHOULD NOT BE CALLED" >&2; return 1; }
_download_via_modelscope() { echo "SHOULD NOT BE CALLED" >&2; return 1; }
sync_model "some/model" "$dir_f" huggingface modelscope install-only >/dev/null 2>&1
check "already-present + install-only mode -> returns 0" "0" "$?"
check "already-present + install-only mode -> existing file untouched" "1" "$([[ -f "$dir_f/weights.bin" ]] && echo 1 || echo 0)"
```

- [ ] **Step 2: Run the tests to confirm the new scenarios fail**

Run: `bash demo/test_updates_opencode.sh && bash demo/test_updates_mlxlm.sh && bash demo/test_updates_model.sh`
Expected: FAIL on the new scenario's first check in each file — since none of the three functions accept a mode argument yet, `install-only` is silently ignored as an unused extra positional parameter and every function falls through to its existing "already present -> attempt upgrade" behavior, so the stubbed-to-fail-loudly upgrade command *is* invoked (the "was NOT invoked" checks report a nonzero count instead of 0).

- [ ] **Step 3: Add the mode parameter to all three functions**

In `demo/openCodeMlx`, replace:

```bash
ensure_opencode_current() {
  if ! command -v opencode >/dev/null 2>&1; then
```

with:

```bash
ensure_opencode_current() {
  local mode="${1:-update}"
  if ! command -v opencode >/dev/null 2>&1; then
```

and replace:

```bash
  echo "Checking for opencode updates..."
  if ! run_with_timeout 20 3 -- opencode upgrade; then
    echo "Warning: opencode upgrade failed or timed out, continuing with existing version." >&2
  fi
  return 0
}
```

with:

```bash
  if [[ "$mode" == "install-only" ]]; then
    return 0
  fi

  echo "Checking for opencode updates..."
  if ! run_with_timeout 20 3 -- opencode upgrade; then
    echo "Warning: opencode upgrade failed or timed out, continuing with existing version." >&2
  fi
  return 0
}
```

Replace:

```bash
ensure_mlx_lm_current() {
  if ! mlx_lm.server --help >/dev/null 2>&1; then
```

with:

```bash
ensure_mlx_lm_current() {
  local mode="${1:-update}"
  if ! mlx_lm.server --help >/dev/null 2>&1; then
```

and replace:

```bash
  echo "Checking for mlx-lm updates..."
  # A successful upgrade here is not proof mlx_lm.server still works: pip
```

with:

```bash
  if [[ "$mode" == "install-only" ]]; then
    return 0
  fi

  echo "Checking for mlx-lm updates..."
  # A successful upgrade here is not proof mlx_lm.server still works: pip
```

Replace:

```bash
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
```

with:

```bash
sync_model() {
  local model_id="$1" local_dir="$2" primary="$3" fallback="$4" mode="${5:-update}"
  local already_present=0
  if [[ -d "$local_dir" && -n "$(ls -A "$local_dir" 2>/dev/null)" ]]; then
    already_present=1
  fi

  if [[ $already_present -eq 1 ]]; then
    if [[ "$mode" == "install-only" ]]; then
      return 0
    fi
    echo "Checking $model_id for updates..."
  else
    echo "Model $model_id not found locally, downloading..."
  fi

  if _download_via "$primary" "$model_id" "$local_dir"; then
```

- [ ] **Step 4: Run the tests to confirm the new scenarios pass**

Run: `bash demo/test_updates_opencode.sh && bash demo/test_updates_mlxlm.sh && bash demo/test_updates_model.sh`
Expected:
```
PASS: not-installed + install produces a binary -> returns 0
PASS: not-installed + install produces no binary -> returns 1 (hard error)
PASS: already-installed + upgrade succeeds -> returns 0
PASS: already-installed + upgrade succeeds -> opencode upgrade was invoked
PASS: already-installed + upgrade fails -> still returns 0 (warn and continue)
PASS: already-installed + install-only mode -> returns 0
PASS: already-installed + install-only mode -> upgrade was NOT invoked
PASS: install fails under set -e -> function returns 1 (not aborted by set -e)
---
8 passed, 0 failed
PASS: not-installed + pip install succeeds -> returns 0
PASS: not-installed + pip install succeeds -> pip was invoked with --upgrade
PASS: not-installed + pip install fails -> returns 1 (hard error)
PASS: already-installed + pip update fails -> still returns 0 (warn and continue)
PASS: already-installed + pip update succeeds -> returns 0
PASS: already-installed -> pip is still invoked (update not skipped)
PASS: already-installed + install-only mode -> returns 0
PASS: already-installed + install-only mode -> pip was NOT invoked
---
8 passed, 0 failed
PASS: not-present + primary succeeds -> returns 0
PASS: not-present + primary succeeds -> file was written
PASS: not-present + primary fails + fallback succeeds -> returns 0
PASS: not-present + both fail -> returns 1 (hard error)
PASS: already-present + primary succeeds -> returns 0
PASS: already-present -> re-sync is attempted, not skipped
PASS: already-present + both fail -> returns 0 (warn, keep existing copy)
PASS: already-present + both fail -> existing file untouched
PASS: already-present + install-only mode -> returns 0
PASS: already-present + install-only mode -> existing file untouched
---
10 passed, 0 failed
```

- [ ] **Step 5: Wire the `update` subcommand into `main()`**

In `demo/openCodeMlx`, replace:

```bash
main() {
  set -e

  if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    echo "MLX requires an Apple Silicon Mac (macOS + arm64), cannot continue"
    exit 1
  fi

  # Ensure opencode bin dir is on PATH (installer adds it to .zshrc but current shell may not have it)
  [[ -d "$HOME/.opencode/bin" ]] && export PATH="$HOME/.opencode/bin:$PATH"

  if ! ensure_opencode_current; then
    exit 1
  fi

  # Set appropriate GPU memory tuning based on the installed memory size
  RAM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo "0")
```

with:

```bash
main() {
  set -e

  local subcommand="${1:-}"
  local ensure_mode="install-only"
  if [[ "$subcommand" == "update" ]]; then
    ensure_mode="update"
  fi

  if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    echo "MLX requires an Apple Silicon Mac (macOS + arm64), cannot continue"
    exit 1
  fi

  # Ensure opencode bin dir is on PATH (installer adds it to .zshrc but current shell may not have it)
  [[ -d "$HOME/.opencode/bin" ]] && export PATH="$HOME/.opencode/bin:$PATH"

  if ! ensure_opencode_current "$ensure_mode"; then
    exit 1
  fi

  if [[ "$subcommand" != "update" ]]; then
  # Set appropriate GPU memory tuning based on the installed memory size
  RAM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo "0")
```

Replace:

```bash
    echo "GPU limit (iogpu.wired_limit_mb) is $current_limit, setting to $GPU_WIRED_LIMIT (requires sudo)..."
    sudo sysctl iogpu.wired_limit_mb=$GPU_WIRED_LIMIT
  fi

  # Set up a dedicated venv for mlx-lm so it doesn't pollute system/other project Python envs
```

with:

```bash
    echo "GPU limit (iogpu.wired_limit_mb) is $current_limit, setting to $GPU_WIRED_LIMIT (requires sudo)..."
    sudo sysctl iogpu.wired_limit_mb=$GPU_WIRED_LIMIT
  fi
  fi

  # Set up a dedicated venv for mlx-lm so it doesn't pollute system/other project Python envs
```

Replace:

```bash
  if ! ensure_mlx_lm_current; then
    exit 1
  fi
```

with:

```bash
  if ! ensure_mlx_lm_current "$ensure_mode"; then
    exit 1
  fi
```

Replace:

```bash
  MLX_PORT="${MLX_PORT:-8090}"

  if lsof -i ":$MLX_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Error: port $MLX_PORT is already in use by another process:"
    lsof -i ":$MLX_PORT" -sTCP:LISTEN
    echo "Set MLX_PORT to a free port and try again."
    exit 1
  fi

  LOCAL_MODEL_DIR="$MLX_DIR/models/$(echo "$MLX_MODEL" | tr '/' '--')"
```

with:

```bash
  if [[ "$subcommand" != "update" ]]; then
    MLX_PORT="${MLX_PORT:-8090}"

    if lsof -i ":$MLX_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Error: port $MLX_PORT is already in use by another process:"
      lsof -i ":$MLX_PORT" -sTCP:LISTEN
      echo "Set MLX_PORT to a free port and try again."
      exit 1
    fi
  fi

  LOCAL_MODEL_DIR="$MLX_DIR/models/$(echo "$MLX_MODEL" | tr '/' '--')"
```

Replace:

```bash
  if ! sync_model "$MLX_MODEL" "$LOCAL_MODEL_DIR" "$PRIMARY" "$FALLBACK"; then
    exit 1
  fi

  # Truncate once per script run; start_mlx_server appends so a crash's trace survives a restart.
```

with:

```bash
  if ! sync_model "$MLX_MODEL" "$LOCAL_MODEL_DIR" "$PRIMARY" "$FALLBACK" "$ensure_mode"; then
    exit 1
  fi

  if [[ "$subcommand" == "update" ]]; then
    echo "Update check complete."
    return 0
  fi

  # Truncate once per script run; start_mlx_server appends so a crash's trace survives a restart.
```

- [ ] **Step 6: Verify the script still parses and the mode wiring is structurally correct**

Run: `bash -n demo/openCodeMlx`
Expected: no output, exit code 0.

Run:
```bash
grep -c 'ensure_opencode_current "\$ensure_mode"' demo/openCodeMlx
grep -c 'ensure_mlx_lm_current "\$ensure_mode"' demo/openCodeMlx
grep -c 'sync_model "\$MLX_MODEL" "\$LOCAL_MODEL_DIR" "\$PRIMARY" "\$FALLBACK" "\$ensure_mode"' demo/openCodeMlx
grep -c 'echo "Update check complete."' demo/openCodeMlx
```
Expected: `1` for each.

- [ ] **Step 7: Commit**

```bash
git add demo/openCodeMlx demo/test_updates_opencode.sh demo/test_updates_mlxlm.sh demo/test_updates_model.sh
git commit -m "feat: add install-only vs update mode; wire openCodeMlx update subcommand

Normal launch now installs-if-missing only (fast, no network calls when
everything's already present). 'openCodeMlx update' runs the full
install-or-upgrade behavior and exits, skipping GPU tuning, the port
check, and starting the server/launching opencode."
```

---

### Task 4: End-to-end `main update` dispatch test; final verification

**Files:**
- Create: `demo/test_main_update.sh`

**Interfaces:**
- Consumes: `main` (Task 3, with mode dispatch wired in).

This is the cross-function confidence check the design calls for: proving the whole dispatch path (arg parsing → mode threading into all three functions → early exit before GPU/server/launch code) actually wires together, not just each function in isolation.

- [ ] **Step 1: Write the test**

Create `demo/test_main_update.sh`:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

home="$work/home"
mkdir -p "$home"

bin="$work/bin"; mkdir -p "$bin"
REAL_PATH="/usr/bin:/bin:/usr/local/bin"

# opencode: already "installed" so ensure_opencode_current takes the
# upgrade branch (update mode requires this to prove upgrade is attempted).
log="$work/calls.log"
make_stub "$bin" opencode 0 "$log"

# sudo: stubbed to fail loudly if invoked, proving the GPU/RAM-tuning block
# (which needs sudo) is genuinely skipped in update mode, not just harmless.
make_stub "$bin" sudo 1 "$log"

# python3: only two call patterns reach it in update mode -- `-m venv <path>`
# (fake up a minimal venv so `source <path>/bin/activate` succeeds) and
# `-c "import modelscope"` (pretend it's already importable, so the
# `pip install modelscope` fallback-installer line is never reached).
cat > "$bin/python3" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == "-m" && "$2" == "venv" ]]; then
  mkdir -p "$3/bin"
  printf '#!/usr/bin/env bash\n:\n' > "$3/bin/activate"
  chmod +x "$3/bin/activate"
  exit 0
fi
if [[ "$1" == "-c" ]]; then
  exit 0
fi
exit 1
EOF
chmod +x "$bin/python3"

# pip: mlx_lm.server won't exist in the fake venv, so ensure_mlx_lm_current
# takes its "not installed" branch, which calls pip install.
make_stub "$bin" pip 0 "$log"

# Model download: override directly (same pattern as test_updates_model.sh)
# rather than touching python3/network for the actual download.
_download_via_huggingface() { echo "download-called" >> "$log"; mkdir -p "$2"; touch "$2/weights.bin"; return 0; }
_download_via_modelscope() { return 1; }

output=$(
  PATH="$bin:$REAL_PATH" HOME="$home" MLX_MODEL="test/model" main update 2>&1
)
status=$?

check "main update exits 0" "0" "$status"
check "main update prints the completion message" "1" "$(echo "$output" | grep -c '^Update check complete\.$')"
check "main update attempted the opencode upgrade (update mode, not install-only)" "1" "$(grep -c '^opencode upgrade$' "$log")"
check "main update attempted the model sync" "1" "$(grep -c '^download-called$' "$log")"
check "main update never invoked sudo (GPU/RAM tuning block was skipped)" "0" "$(grep -c '^sudo ' "$log")"
check "main update did not print GPU-tuning output (block was skipped, not just harmless)" "0" "$(echo "$output" | grep -c 'Detected.*RAM')"

report
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `bash demo/test_main_update.sh`
Expected: FAIL — before this task there's no reason to expect this exact combination to already work end-to-end (this is the first test that exercises `main` directly rather than an individual function), so run it to see where it actually breaks before assuming it passes. If it hangs or takes more than a few seconds, stop it (Ctrl-C) and re-check the stub setup rather than assuming it will eventually finish — nothing here should block.

- [ ] **Step 3: Fix anything the test surfaces**

If Step 2 fails only because of a genuine gap in the stub setup (not a bug in `demo/openCodeMlx` itself), fix the test. If it reveals an actual bug in `main`'s dispatch logic from Task 3, fix `demo/openCodeMlx` and re-run the full test suite from Task 3 (`test_updates_opencode.sh`, `test_updates_mlxlm.sh`, `test_updates_model.sh`) as well as this one to confirm nothing regressed.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `bash demo/test_main_update.sh`
Expected:
```
PASS: main update exits 0
PASS: main update prints the completion message
PASS: main update attempted the opencode upgrade (update mode, not install-only)
PASS: main update attempted the model sync
PASS: main update never invoked sudo (GPU/RAM tuning block was skipped)
PASS: main update did not print GPU-tuning output (block was skipped, not just harmless)
---
6 passed, 0 failed
```

- [ ] **Step 5: Run the full test suite one final time**

```bash
bash -n demo/openCodeMlx
bash demo/test_main_guard.sh
bash demo/test_timeout.sh
bash demo/test_updates_opencode.sh
bash demo/test_updates_mlxlm.sh
bash demo/test_updates_model.sh
bash demo/test_main_update.sh
```
Expected: all seven commands succeed, each test file reporting `0 failed`.

Also confirm the original bug report is actually fixed, with zero external file dependencies now (this was the whole reason for this plan): if a symlink to `demo/openCodeMlx` exists anywhere (e.g. `~/.local/bin/openCodeMlx` from earlier testing), running `bash -n` through it should still parse cleanly, and there is no `lib/` directory reference left anywhere in the file to break:

```bash
grep -c 'lib/updates.sh\|lib/timeout.sh\|resolve_script_dir' demo/openCodeMlx
```
Expected: `0`.

- [ ] **Step 6: Commit**

```bash
git add demo/test_main_update.sh
git commit -m "test: add end-to-end main update dispatch test"
```
