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
