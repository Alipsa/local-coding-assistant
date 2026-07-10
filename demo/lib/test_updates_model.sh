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
