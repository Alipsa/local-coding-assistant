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

report
