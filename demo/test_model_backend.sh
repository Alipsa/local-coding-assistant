#!/usr/bin/env bash
set -e
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
model_dir="$fixture_dir/model's directory"
mkdir -p "$model_dir"
printf '%s\n' '{"vision_config": {}}' > "$model_dir/config.json"
check "vision model in apostrophe path" "mlx_vlm" "$(detect_model_backend "$model_dir")"
printf '%s\n' '{"model_type": "qwen"}' > "$model_dir/config.json"
check "text model in apostrophe path" "mlx_lm" "$(detect_model_backend "$model_dir")"
printf '%s\n' '{invalid' > "$model_dir/config.json"
check "invalid config keeps text fallback" "mlx_lm" "$(detect_model_backend "$model_dir")"
rm "$model_dir/config.json"
check "missing config keeps text fallback" "mlx_lm" "$(detect_model_backend "$model_dir")"
report
