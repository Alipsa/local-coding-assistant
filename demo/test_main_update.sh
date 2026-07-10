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
check "main update attempted the model sync for both main and small models" "2" "$(grep -c '^download-called$' "$log")"
check "main update never invoked sudo (GPU/RAM tuning block was skipped)" "0" "$(grep -c '^sudo ' "$log")"
check "main update did not print GPU-tuning output (block was skipped, not just harmless)" "0" "$(echo "$output" | grep -c 'Detected.*RAM')"

report
