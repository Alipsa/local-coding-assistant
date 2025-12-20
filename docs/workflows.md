# Workflows

These workflows describe the expected CLI usage patterns and outputs.

## Edit workflow (Search-and-Replace blocks)
1. Use `/context` or `/codesearch` to collect the relevant snippet.
2. Ask `/chat` for Search-and-Replace blocks:
   - Format:
     <<<<SEARCH
     original block
     ====
     new block
     >>>>
3. Preview changes with `/applyBlocks --dry-run true`.
4. Confirm and apply the blocks with `/applyBlocks --dry-run false`.
5. Use `/revert` if you need to roll back to the latest backup.

Notes:
- Edit commands print a progress indicator and return a sectioned `Edit Preview` or `Edit Result` block.
- Confirm prompts use `[y/N/a]` to prevent accidental changes.

## Review workflow
1. Run `/review` with `--paths` and an explicit prompt.
2. Use `--min-severity` to focus on higher impact findings.
3. Query `/reviewlog` to recall recent reviews and filter by severity or path.

Notes:
- Review output is sectioned and includes `Findings` and `Tests` sections.
- The log stores the structured output without ANSI color codes.

## Search and context workflow
1. Use `/codesearch` for local ripgrep-powered search.
2. If web search is enabled, use `/search` for external references.
3. Pack matches with `--pack` to build a single context blob.

Notes:
- Search output includes counts and result numbering.
- Web search can be disabled at the session or request level.

## Git workflow
1. Check repository state with `/status`.
2. Review changes using `/diff` (or `/diff --staged`).
3. Stage files or hunks via `/stage`.
4. Generate a commit message with `/commit-suggest`.
5. Push with `/git-push` (requires confirmation).

Notes:
- Commands warn when the workspace is dirty before applying patches.
- Confirmation prompts are mandatory for destructive or risky operations.

## Run and diagnostics workflow
1. Execute commands with `/run`, using `--timeout-millis` as needed.
2. Review the output and log path returned in the `Run` section.

Notes:
- Output is truncated for long runs; logs keep full content.
- Use confirmations when commands originate from agent requests.
