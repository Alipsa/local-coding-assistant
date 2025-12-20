# Command Reference

All commands are available as `command` or `/command`. Options use Spring Shell naming (kebab-case).
Examples use named options for consistency.

## chat (/chat)
Send a prompt to the assistant.

Usage:
`chat --prompt "<text>"`

Options:
- `--session`: Session id for persisting options.
- `--persona`: Persona mode (`CODER`, `ARCHITECT`, `REVIEWER`).
- `--model`: Override model for the session.
- `--temperature`: Override craft temperature.
- `--review-temperature`: Override review temperature.
- `--max-tokens`: Override max tokens.
- `--system-prompt`: Extra system prompt guidance.

## review (/review)
Request a structured review with findings and tests.

Usage:
`review --prompt "<text>" [--code "<code>"]`

Options:
- `--prompt`: Review request or guidance.
- `--paths`: File paths to include in context (repeatable).
- `--staged`: Include staged git diff.
- `--min-severity`: Minimum severity (`LOW`, `MEDIUM`, `HIGH`).
- `--no-color`: Disable ANSI colors.
- `--log-review`: Persist review summary to log.
- `--security`: Focus on security risks in the review.
- `--session`, `--model`, `--review-temperature`, `--max-tokens`, `--system-prompt`.

## reviewlog (/reviewlog)
Show recent reviews from the log with filters.

Options:
- `--min-severity`: Minimum severity to show.
- `--path-filter`: Filter by path substring.
- `--limit`: Maximum entries to show.
- `--page`: Page number (1-based).
- `--since`: ISO-8601 timestamp (e.g., `2025-02-12T10:00:00Z`).
- `--no-color`: Disable ANSI colors.

## search (/search)
Run web search through the agent tool.

Usage:
`search --query "<text>"`

Options:
- `--limit`: Number of results to show.
- `--provider`: Search provider (default `duckduckgo`).
- `--timeout-millis`: Timeout in milliseconds.
- `--headless`: Run browser in headless mode.
- `--enable-web-search`: Override web search enablement.
- `--session`: Session id for caching and configuration.

## codesearch (/codesearch)
Search repository files with ripgrep and optional context packing.

Usage:
`codesearch --query "<pattern>"`

Options:
- `--paths`: Paths or globs to search (repeatable).
- `--context`: Context lines around matches.
- `--limit`: Maximum matches to return.
- `--pack`: Pack results into a single context blob.
- `--max-chars`: Max characters when packing.
- `--max-tokens`: Max tokens when packing (0 uses default).

## edit (/edit)
Open `$EDITOR` to draft a prompt.

Usage:
`edit [--seed <text>] [--send]`

Options:
- `--seed`: Prefill the editor with content.
- `--send`: Send the edited text to `/chat` when done.
- `--session`: Session id.
- `--persona`: Persona when sending.

## paste (/paste)
Paste multiline input (end with `/end`).

Usage:
`paste [--content <text>] [--send]`

Options:
- `--content`: Prefilled content (otherwise read from stdin).
- `--end-marker`: Line that terminates paste mode.
- `--send`: Send pasted content to `/chat`.
- `--session`: Session id.
- `--persona`: Persona when sending.

## status (/status)
Show git status.

Options:
- `--short-format`: Use porcelain output.

## diff (/diff)
Show git diff.

Options:
- `--staged`: Use staged diff (`--cached`).
- `--context`: Number of context lines.
- `--paths`: Paths to include (repeatable).
- `--stat`: Show stats instead of full patch.

## gitapply (/gitapply)
Apply a patch using `git apply` with confirmation.

Usage:
`gitapply [--patch "<text>"] [--patch-file <path>]`

Options:
- `--patch-file`: Patch file relative to project root.
- `--cached`: Apply to index (`--cached`).
- `--check`: Run `git apply --check` before writing.
- `--confirm`: Ask for confirmation before applying.

## stage (/stage)
Stage files or specific hunks with confirmation.

Options:
- `--paths`: File paths to stage (repeatable).
- `--file`: File to stage hunks from.
- `--hunks`: Comma-separated hunk numbers to stage.
- `--confirm`: Ask for confirmation.

## commit-suggest (/commit-suggest)
Draft an imperative commit message from staged changes.

Options:
- `--session`, `--model`, `--temperature`, `--max-tokens`.
- `--hint`: Optional guidance for the commit message.
- `--secret-scan`: Scan staged diff for secrets before suggesting.
- `--allow-secrets`: Allow suggestion even if secrets are detected.

## git-push (/git-push)
Push the current branch with confirmation.

Options:
- `--force`: Use `--force-with-lease`.

## model (/model)
List available models or switch the active one.

Options:
- `--list`: List available models.
- `--set`: Set the session model.
- `--session`: Session id.

## health (/health)
Check connectivity to the Ollama base URL.

## run (/run)
Execute a project command with timeout and truncation.

Usage:
`run --command "<command>"`

Options:
- `--timeout-millis`: Timeout in milliseconds.
- `--max-output-chars`: Maximum output characters to display.
- `--confirm`: Ask for confirmation before running.
- `--agent-requested`: Mark that the request came from the agent.
- `--session`: Session id for history logging.

## apply (/apply)
Apply a unified diff patch with optional dry run and backups.

Usage:
`apply [--patch "<text>"] [--patch-file <path>]`

Options:
- `--patch-file`: Patch file relative to project root.
- `--dry-run`: Preview without writing.
- `--confirm`: Ask for confirmation.

## applyBlocks (/applyBlocks)
Apply Search-and-Replace blocks to a file.

Usage:
`applyBlocks --file-path <path> [--blocks <text>] [--blocks-file <path>]`

Options:
- `--file-path`: Target file path relative to project root.
- `--blocks`: Blocks text.
- `--blocks-file`: File containing blocks.
- `--dry-run`: Preview without writing.
- `--confirm`: Ask for confirmation.

## revert (/revert)
Restore a file using the most recent patch backup.

Usage:
`revert --file-path <path>`

Options:
- `--file-path`: File path relative to project root.
- `--dry-run`: Preview without writing.

## context (/context)
Show a snippet for targeted edits by line range or symbol.

Usage:
`context --file-path <path> [--start <n> --end <n>] [--symbol <name>]`

Options:
- `--file-path`: File path relative to project root.
- `--start`: Start line (1-based) when using ranges.
- `--end`: End line (1-based) when using ranges.
- `--symbol`: Symbol to locate instead of line numbers.
- `--padding`: Padding lines around the selection.

## tree (/tree)
Show repository tree (respects `.gitignore` when available).

Usage:
`tree [--depth <n>] [--dirs-only] [--max-entries <n>]`

Options:
- `--depth`: Max depth (`-1` for unlimited).
- `--dirs-only`: Show directories only.
- `--max-entries`: Maximum entries to render (`0` for unlimited).
