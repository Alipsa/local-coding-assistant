#!/usr/bin/env bash
set -e
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./test_helpers.sh
source ./openCodeMlx

# Stub the Python invocation; no model downloads or server processes are needed.
stub_dir=$(mktemp -d)
trap 'rm -rf "$stub_dir"' EXIT
cat > "$stub_dir/python3" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@"
STUB
chmod +x "$stub_dir/python3"
export PATH="$stub_dir:$PATH"
unset MLX_VLM_TRUST_REMOTE_CODE
check "remote code disabled by default" $'-m\nmlx_vlm.server\n--model\nlocal-model' \
  "$(run_mlx_vlm_server --model local-model)"
export MLX_VLM_TRUST_REMOTE_CODE=1
check "explicit opt-in enables remote code" $'-m\nmlx_vlm.server\n--trust-remote-code\n--model\nlocal-model' \
  "$(run_mlx_vlm_server --model local-model)"
export MLX_VLM_TRUST_REMOTE_CODE=0
check "zero keeps remote code disabled" $'-m\nmlx_vlm.server\n--model\nlocal-model' \
  "$(run_mlx_vlm_server --model local-model)"
export MLX_VLM_TRUST_REMOTE_CODE=true
check "only one enables remote code" $'-m\nmlx_vlm.server\n--model\nlocal-model' \
  "$(run_mlx_vlm_server --model local-model)"
report
