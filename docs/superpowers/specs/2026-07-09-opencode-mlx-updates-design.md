# openCodeMlx: keep opencode, mlx-lm, and the model up to date

## Goal

`demo/openCodeMlx` currently only installs opencode, mlx-lm, and the model
weights the *first* time it runs — on every subsequent run it detects they
already exist and skips straight to launch. This means the script can drift
onto stale versions indefinitely. It should instead check for and apply
updates to all three on every run.

## Constraint: `set -e` (line 7)

The script runs under `set -e`, so a bare `opencode upgrade` or
`pip install --upgrade ...` statement that fails would abort the whole
script — directly contradicting the "warn and continue" requirement below.
Every update command must be written so its exit status is explicitly
handled, not left bare:

```bash
if ! opencode upgrade; then
  echo "Warning: opencode upgrade failed, continuing with existing version." >&2
fi
```

or the equivalent `cmd || { warn; }` form. This mirrors the pattern the
existing code already uses for model downloads (`if ! download_model ...`).

## Behavior per component

Each component tracks an explicit "already present" boolean *before*
attempting its update, captured via the same checks the script uses today.
That flag is what decides hard-error-vs-warn if the update attempt fails —
it is not being removed, only repurposed.

**opencode** — the existing "install via curl installer if `opencode` isn't
on PATH" branch is unchanged (this *is* the "not already present" case). Add
an `else` branch (opencode already installed) that runs `opencode upgrade`,
wrapped per the `set -e` note above. A failed upgrade here only warns,
because opencode is already present and usable.

**mlx-lm** — keep the existing `mlx_lm.server --help` check as the
already-present flag, but change what each branch does:
- Not present: `pip install --upgrade pip mlx-lm "transformers>=5.7,<5.13"`;
  if this fails, hard error (nothing to fall back to; existing behavior).
- Already present: run the same `pip install --upgrade ...` command, wrapped
  so a failure only warns and continues with the existing installation.

**Model weights** — keep the existing `LOCAL_MODEL_DIR` missing/empty check
as the already-present flag:
- Not present: download via the primary source, falling back to the
  secondary on failure; if both fail, hard error (existing behavior).
- Already present: attempt the same primary→fallback download sequence
  (this re-syncs any changed files, see below); if both fail, warn and
  continue using the existing local copy.

## Network resilience: bounded timeouts

All three update operations (`opencode upgrade`, `pip install --upgrade`,
and the HF/ModelScope re-sync) must run under an explicit timeout so a
degraded or unreachable network fails fast into the warn-and-continue path
instead of hanging. This was not just theoretical: during design, a test
`snapshot_download` call against a small ModelScope repo hung for over two
minutes with no output, even though a plain `curl` to `modelscope.cn`
resolved in under a second — the library's own retry/timeout behavior is not
something we can rely on to fail quickly.

Since macOS ships BSD tools (no GNU `coreutils timeout` by default), the
implementation needs a small portable timeout wrapper. A plain
background-command + `sleep N` watchdog + single `kill` is not sufficient:
`pip install` and the `python3 -c "...snapshot_download..."` calls can spawn
subprocesses, and a process blocked in a C-level blocking socket read can
ignore a bare SIGTERM until the syscall returns — which may be exactly what
we saw in the 2-minute hang described above. The wrapper must:

1. Run the command in its own process group (e.g. via `set -m` job control
   so a backgrounded command becomes its own process-group leader).
2. On timeout, send SIGTERM to the whole group (`kill -TERM -$pid`), not
   just the direct child PID.
3. Give it a short grace period (e.g. 2-3s), then escalate to SIGKILL on the
   group (`kill -KILL -$pid`) if it hasn't exited.

Suggested budgets: ~20s for `opencode upgrade` and `pip install --upgrade`,
longer (e.g. ~90s) for the model re-sync since a real content change may
need to transfer data, not just check a hash. Exact values, and the
wrapper's implementation, are an implementation-plan detail, not fixed here
— but the TERM-then-KILL-on-process-group escalation is a hard requirement,
not optional polish, since without it "bounded timeout" may not actually
bound anything for a hung child process.

Note the ~20s pip budget is tuned for the common "nothing to upgrade"
case, where pip just checks the index and exits. When an upgrade *is*
available — `transformers` isn't tiny — a real download+install on a normal
connection could plausibly exceed 20s, tripping the timeout and
warn-and-skipping a perfectly healthy upgrade (indistinguishable here from
a genuine network failure; the fallback behavior is the same and safe
either way). If this turns out to happen routinely in practice, loosen the
pip budget rather than the process-group-kill logic.

## Separate, debatable decision: model download order default

This is called out separately from the rest of this spec because it's
driven by one environment's constraint (HF being blocked here, presumably a
corporate proxy), not by the update-checking feature itself, and it changes
a *shared* default rather than this environment's behavior. It should be
reviewable/revertable independently of the update-checking work above.

The script currently defaults to `PRIMARY=huggingface, FALLBACK=modelscope`
unless `MLXLM_USE_MODELSCOPE=true`. Because the update check now runs the
primary→fallback sequence on *every* launch (not just the first), an
unreachable primary source turns into recurring per-run latency instead of
a one-time cost — for this author's environment specifically. This change
flips the script's default to `PRIMARY=modelscope, FALLBACK=huggingface`
(`MLXLM_USE_MODELSCOPE` still overrides it, e.g. set it to `false` on a
machine where HF is reachable and preferred).

Risk: ModelScope may not mirror every `mlx-community` quantization as
promptly or completely as HF does, so on a machine with working HF access
this default could regress the common case (slower/missing model
availability) in exchange for saving latency in one blocked environment. If
this trade-off turns out to be wrong in practice, revert just this section's
change (swap `PRIMARY`/`FALLBACK` back) — it's independent of everything
else in this spec.

## Incremental re-sync: what was verified

The "re-syncing an existing model is cheap" premise was checked against the
installed libraries' source rather than assumed:

- ModelScope's client (`modelscope_hub/_download.py`, `_cache_hit`) skips
  re-downloading a file if it already exists locally, hash-verifying against
  a server-supplied sha256 when one is available. This confirms per-file
  skip-if-unchanged behavior exists.
- This check still requires a live file-listing round trip per run, and,
  when the server does supply a sha256, a local hash computation over the
  existing (potentially multi-GB) file(s) — cheap in network bytes, but not
  free in CPU/wall time. This is a real but bounded cost, not a bug.
- HF's `huggingface_hub.snapshot_download` has an equivalent local-dir cache
  design; not independently re-verified against source in this pass.
- What was *not* confirmed empirically end-to-end: a live timed re-sync
  against one of the actual `MLX_MODEL` candidates (only a small unrelated
  test repo was tried, and that single attempt took >2 minutes with no
  clear completion). The bounded-timeout requirement above is the safety
  net specifically because this couldn't be fully verified live — if the
  incremental skip doesn't perform as expected for a given model repo, the
  timeout still caps the damage to one slow run rather than an indefinite
  hang, and the run falls through to the existing local copy.

## Accepted risk: a successful upgrade isn't a verified-safe upgrade

Error handling above only covers `pip install --upgrade` *failing*. It does
not cover the command succeeding but landing a new `mlx-lm`/`transformers`
combination that's functionally broken in a way pip can't detect at install
time — e.g. `mlx_lm.server` fails to start, or crashes in a new way
`diagnose_server_crash` doesn't recognize (it only pattern-matches known OOM
signatures, not arbitrary regressions from a library upgrade).

Before this change, once installed, the script always ran a known-good
pinned combination indefinitely. After this change, every launch carries a
small but real chance of a self-inflicted regression that the warn-and-continue
logic will not catch, because pip reported success — the failure would
surface later, as a normal (non-OOM) server crash with no indication it was
caused by that run's upgrade. This is accepted as a reasonable trade-off for
staying current, not mitigated by this change (e.g. no automatic
rollback-on-first-launch-failure is implemented). If this proves too
disruptive in practice, a follow-up could snapshot the previously-working
`pip freeze` output and offer to reinstall it on repeated post-upgrade
crashes — out of scope here.

## Error handling summary

A failed update check warns (to stderr) and continues using whatever is
already installed/downloaded, rather than aborting the script — this keeps
the demo usable offline or when a registry has a transient issue or a
timeout trips. The exception is when there's nothing to fall back to:

- opencode was never installed and the curl installer fails → hard error
  (existing behavior, unchanged).
- mlx-lm was never installed and `pip install` fails → hard error.
- The model was never downloaded and both sources fail → hard error
  (existing behavior, unchanged).

## Out of scope

- `demo/openCodeLocal` (ollama-based) already has its own update pattern
  (`ollama pull` + digest comparison) and is not touched by this change.
- The `modelscope` pip package installer step (a download-fallback
  dependency, not one of the three components the user asked to keep
  current) keeps its "install only if missing" behavior unchanged.
- No new flag/env var to opt out of update-checking entirely is introduced;
  update checks always run (per explicit user preference), bounded by the
  timeouts above so the cost of a failed/slow check stays low.
