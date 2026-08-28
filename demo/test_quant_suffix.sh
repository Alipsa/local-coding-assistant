#!/usr/bin/env bash
set -u
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

check "extracts a 4bit suffix" "4bit" "$(_quant_suffix 'mlx-community/Qwen3.8-27B-4bit')"
check "extracts an 8bit suffix" "8bit" "$(_quant_suffix 'mlx-community/Qwen3.8-27B-8bit')"
check "extracts a multi-digit bit suffix" "16bit" "$(_quant_suffix 'mlx-community/Some-Model-16bit')"
check "empty for a model id with no quant suffix" "" "$(_quant_suffix 'mlx-community/Qwen3-Coder-Next')"
check "does not match a mid-string bit token" "" "$(_quant_suffix 'mlx-community/8bit-prefixed-model')"
check "extracts the bit count from a DWQ-qualified id" "4bit" "$(_quant_suffix 'mlx-community/Qwen3.8-27B-4bit-DWQ')"
check "extracts the bit count from an underscore-qualified id" "8bit" "$(_quant_suffix 'mlx-community/Qwen3.8-27B-8bit_dwq')"
check "does not treat a multi-word non-quant tail as a qualifier" "" "$(_quant_suffix 'mlx-community/8bit-prefixed-model-name')"

# Regression guard: main() runs under `set -e`, and its call sites assign the result via bare
# "main_quant=$(_quant_suffix "$MLX_MODEL")" - not inside an if/while condition, so under set -e
# the assignment's exit status must never be non-zero, or a no-match model id (any checkpoint
# id that doesn't end in "<N>bit") would silently kill the whole script right after the
# multi-GB model download, with no error message and no cleanup trap installed yet.
_quant_suffix 'mlx-community/Qwen3.8-27B-4bit' >/dev/null
check "exits 0 on a matching model id" "0" "$?"
_quant_suffix 'mlx-community/Qwen3-Coder-Next' >/dev/null
check "exits 0 (not 1) on a non-matching model id, so set -e callers survive" "0" "$?"
check "a bare assignment under set -e still reaches the next line" "REACHED" \
  "$(set -e; no_suffix_quant=$(_quant_suffix 'mlx-community/Qwen3-Coder-Next'); echo "REACHED")"

report
