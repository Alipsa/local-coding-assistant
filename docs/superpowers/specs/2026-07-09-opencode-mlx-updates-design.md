# openCodeMlx: keep opencode, mlx-lm, and the model up to date

## Goal

`demo/openCodeMlx` currently only installs opencode, mlx-lm, and the model
weights the *first* time it runs — on every subsequent run it detects they
already exist and skips straight to launch. This means the script can drift
onto stale versions indefinitely. It should instead check for and apply
updates to all three on every run.

## Behavior per component

**opencode** — the existing "install via curl installer if `opencode` isn't
on PATH" branch is unchanged. Add an `else` branch (opencode already
installed) that runs `opencode upgrade`.

**mlx-lm** — replace the guard that only installs when `mlx_lm.server --help`
fails with an unconditional
`pip install --upgrade pip mlx-lm "transformers>=5.7,<5.13"` on every run.
`pip install --upgrade` is a fast no-op when the installed version is already
current, so this doubles as both the first-install path and the update path.

**Model weights** — replace the guard that only calls `download_model` when
`LOCAL_MODEL_DIR` is missing/empty with an unconditional call every run, using
the existing HF → ModelScope fallback order (or the reverse, per
`MLXLM_USE_MODELSCOPE`). Both `huggingface_hub.snapshot_download` and
ModelScope's `snapshot_download` only re-fetch files that changed upstream
when pointed at an existing `local_dir`, so this is cheap when the model
hasn't changed.

## Error handling

A failed update check warns (to stderr) and continues using whatever is
already installed/downloaded, rather than aborting the script — this keeps
the demo usable offline or when a registry has a transient issue. The
exception is when there's nothing to fall back to:

- opencode was never installed and the curl installer fails → hard error
  (existing behavior, unchanged).
- mlx-lm was never installed and `pip install` fails → hard error.
- The model was never downloaded and both HF and ModelScope fail → hard error
  (existing behavior, unchanged).

When something *is* already present, a failed update attempt (opencode
upgrade, pip upgrade, or the model re-sync via HF+ModelScope) only warns and
falls through to using the existing install/model.

## Out of scope

- `demo/openCodeLocal` (ollama-based) already has its own update pattern
  (`ollama pull` + digest comparison) and is not touched by this change.
- The `modelscope` pip package is a download-fallback dependency, not one of
  the three components the user asked to keep current — its "install only if
  missing" behavior is unchanged.
- No new flags/env vars to opt out of update-checking are introduced; update
  checks always run (per explicit user preference), and their cost is
  expected to be low given the incremental-sync behavior described above.
