# openCodeMlx: inline the update library and make updates an explicit subcommand

## Background

The previous feature (see `2026-07-09-opencode-mlx-updates-design.md`) split
update-checking logic out of `demo/openCodeMlx` into a sourced library
(`demo/lib/timeout.sh`, `demo/lib/updates.sh`) so it could be unit-tested in
isolation, and wired it to run automatically on every launch.

Two things changed since then:

1. **A real bug surfaced in production use.** `demo/openCodeMlx` is normally
   invoked through a symlink in `~/.local/bin` (the established pattern for
   all of this repo's `demo/` launcher scripts). `${BASH_SOURCE[0]}` does not
   follow symlinks, so `dirname "${BASH_SOURCE[0]}")/lib/updates.sh` resolved
   to the symlink's own directory (`~/.local/bin/lib/updates.sh`) instead of
   the repo's `demo/lib/updates.sh`, breaking every symlinked invocation.
2. **The user reconsidered the always-on-every-launch behavior.** Rather
   than patch the symlink bug in place, the decision is to inline everything
   into the single script (removing the sourced-file dependency entirely)
   and make updating an explicit, separate action from launching.

## Goal

- `demo/openCodeMlx` becomes a single self-contained file — no `demo/lib/`,
  no sourcing, no symlink-resolution concerns.
- `openCodeMlx update` — a new subcommand — runs the full install-or-upgrade
  behavior for opencode, mlx-lm, and the model, then exits without launching
  anything.
- A normal launch (`openCodeMlx` with no subcommand, or with args destined
  for opencode itself) goes back to the original pre-feature behavior:
  install each component only if it's missing; if it's already present, use
  it as-is with no update check and no extra network calls. This restores
  fast, offline-safe normal launches.

## Behavior per mode

Each of `ensure_opencode_current`, `ensure_mlx_lm_current`, and `sync_model`
takes a mode argument, `install-only` or `update` (default `update` if
omitted, matching the function names' own implication):

- **Not present, either mode:** identical — install it. Hard error if the
  install itself fails (nothing to fall back to). This path is unchanged
  from the existing, already-reviewed implementation.
- **Already present, `install-only` mode:** return success immediately, no
  upgrade attempt, no network call.
- **Already present, `update` mode:** identical to today's behavior — best
  effort upgrade attempt (bounded by `run_with_timeout`), warn and continue
  on failure.

This keeps the "not present" install logic — including the `_install_opencode_via_curl`
override pattern and the `set -e`-safety fix from that path's prior
review — in exactly one place per function; only the "already present"
branch gates on mode.

## `main()` and the sourcing guard

All function definitions (the timeout helper, the three `ensure_*`/`sync_model`
functions, and their private helpers) move to the top of `demo/openCodeMlx`.
The script's actual top-to-bottom behavior — arm64 check, PATH bootstrap,
GPU/RAM tuning, starting `mlx_lm.server`, launching `opencode` — moves into
a `main()` function that takes over what today runs at the top level. A
trailing guard runs `main` only when the file is executed, not sourced:

```bash
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
```

This serves two purposes at once: it's what makes inlining safe for testing
(tests `source demo/openCodeMlx`, which now only defines functions and does
nothing else, then call individual functions or `main update` directly —
exactly today's test pattern, just against one file instead of two), and it
eliminates the symlink bug's root cause outright (there is no longer a
companion file to locate relative to the script's own path).

`main()`'s dispatch: if `$1` is literally `update`, run the arm64 check,
opencode PATH bootstrap, venv setup, and all three `ensure_*`/`sync_model`
calls in `update` mode, print a short completion message, and exit — skipping
GPU/RAM sysctl tuning (which needs `sudo` and is irrelevant to an update
check), the port-availability check, and starting the server/launching
opencode entirely. Any other value of `$1` (including no arguments) runs the
full existing flow with `install-only` mode, then reaches
`opencode --pure "$@"` exactly as it does today — arguments meant for
opencode itself (e.g. a project path) are unaffected by this change.

## File structure

- `demo/lib/timeout.sh`, `demo/lib/updates.sh`, and their four test files
  (`demo/lib/test_timeout.sh`, `test_updates_opencode.sh`,
  `test_updates_mlxlm.sh`, `test_updates_model.sh`) are deleted.
- Four replacement test files live alongside the script:
  `demo/test_timeout.sh`, `demo/test_updates_opencode.sh`,
  `demo/test_updates_mlxlm.sh`, `demo/test_updates_model.sh`. Each does
  `source demo/openCodeMlx` instead of sourcing a lib file; the PATH-stub
  and function-override patterns proven out in the original feature
  (avoiding PATH-stubbed `curl`/`bash` by name, real system dirs alongside
  stub dirs so `run_with_timeout`'s own `mktemp`/`sleep`/`kill` still
  resolve) carry over unchanged.

## Testing the mode split

Each function's existing "already present" scenarios split into two:

- `install-only` mode: stub the upgrade command (`opencode upgrade` /
  `pip install --upgrade ...` / the download functions) to fail loudly if
  invoked, and assert it was *not* called — proving the skip is real, not
  coincidental.
- `update` mode: unchanged from today — asserts the upgrade *is* attempted,
  both on success and on failure (warn-and-continue).

One additional test exercises `main update` end-to-end (with opencode/pip/
mlx_lm.server/download functions all stubbed) to confirm the full dispatch
path — argument parsing, mode threading into all three functions, the early
exit before GPU/server/launch code — actually wires together, not just each
function in isolation.

## Accepted risk (unchanged from the original design)

A successful `pip install --upgrade mlx-lm` during `openCodeMlx update` is
still not proof `mlx_lm.server` works; this isn't mitigated by this change
either, same as documented in the original design and now also as a code
comment in `ensure_mlx_lm_current`.

## Out of scope

- No change to `demo/openCodeLocal` (unaffected by this file, untouched
  either way).
- No change to the ModelScope-primary default flip from the original
  feature (Task 6) — that stays as-is, orthogonal to this restructuring.
- No new flag beyond the single `update` subcommand; no `--dry-run`,
  no per-component update flags (e.g. "just check opencode"). If finer
  granularity is wanted later, that's a follow-up, not part of this change.
