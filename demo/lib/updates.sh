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
