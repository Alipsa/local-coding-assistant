# PR Review Support for LCA

## Problem

When a user asks LCA to `review PR #16`, the review command has no way to fetch the PR diff from GitHub. It passes "PR #16" as prompt text with no code payload, so the model finds nothing to review. In contrast, tools like Claude Code can produce deep, cross-file analysis because they have access to the full diff and changed file contents.

## Design Decisions

- **PR diffs are fetched via `gh pr diff N`** (GitHub CLI). LCA is local-first but PRs live on GitHub.
- **Changed file contents are included** alongside the diff so the model can reason about surrounding code and cross-file interactions.
- **Context budget of 80k chars** (configurable via `assistant.llm.review-pr-context-budget`). If the diff + file contents exceed the budget, file contents are dropped and only the diff is used, with a warning.
- **Intent router detects `PR #N`** in natural language and emits `--pr N` on the `/review` command.
- **No word limit for PR reviews.** The `reviewWordCount` enforcement is skipped.
- **Output focuses on issues and actions only.** The prompt instructs the model not to list strengths or summarise what the PR does well.

## Files Changed

### 1. GitTool.groovy

Add two new methods:

```groovy
GitResult prDiff(int prNumber)
```
Runs `gh pr diff <N>` in the project root directory. Returns the unified diff as `GitResult.output`.

```groovy
GitResult prChangedFiles(int prNumber)
```
Runs `gh pr view <N> --json files --jq '.files[].path'` to get the list of changed file paths.

Both methods use a private `runCommand(List<String>)` helper (similar to `runGit` but without prepending `git`) since `gh` is a separate binary. Alternatively, reuse `ProcessBuilder` directly within these methods.

Error handling:
- If `gh` is not installed, the process fails with an IOException. Return `GitResult(false, true, 1, "", "GitHub CLI (gh) is required for PR reviews. Install it from https://cli.github.com/")`.
- If the PR does not exist, `gh` returns exit code 1 with an error message. Pass through as-is.

### 2. IntentCommandMapper.groovy

Update `buildReviewCommand()` to detect `PR #N`, `PR#N`, or `PR N` patterns (case-insensitive) in the prompt. When detected, append `--pr <N>` to the generated command.

```groovy
def prMatcher = prompt =~ /(?i)\bPR\s*#?\s*(\d+)\b/
if (prMatcher.find()) {
  builder.append(" --pr ").append(prMatcher.group(1))
}
```

### 3. CommandExecutor.groovy

Update `executeReview()` to parse the `pr` parameter from `parsed` and pass it to `shellCommands.review()`.

### 4. ReviewRequest.groovy

Add a `boolean prReview` field (default `false`).

### 5. ShellCommands.groovy

#### New `--pr` option on `review()`

```groovy
@ShellOption(defaultValue = ShellOption.NULL, help = "GitHub PR number to review") Integer pr
```

#### Updated `buildReviewPayload()`

When `pr` is set:
1. Call `gitTool.prDiff(pr)` to get the unified diff.
2. Call `gitTool.prChangedFiles(pr)` to get the list of changed file paths.
3. Read each changed file via `fileEditingTool.readFile()`, collecting content into the payload.
4. Append the unified diff.
5. If total payload size exceeds the context budget (default 80k chars from `assistant.llm.review-pr-context-budget`), drop file contents and keep only the diff. Print a warning: "PR is large; reviewing diff only (file contents exceeded context budget)".

If `prDiff()` fails (e.g. `gh` not installed), return early with an error message.

#### Skip word limit for PR reviews

Pass `prReview=true` on the `ReviewRequest`. In `enforceReviewFormat()`, skip `enforceWordLimit()` when `prReview` is true.

### 6. CodingAssistantAgent.groovy

#### Updated `buildReviewPrompt()`

When `ReviewRequest.prReview` is true, append PR-specific instructions after the base prompt:

```
This is a pull request review. Analyse the full diff and all changed files provided below.

Cross-file analysis:
- Trace data flow across changed files. If a method signature or column changes in one file, verify all callers/consumers are updated.
- Check constraint consistency: if a unique constraint changes, verify all queries that depend on uniqueness.
- Look for missing filters: if a table now requires a compound key, verify all lookups include all key columns.
- Identify silent failures: null returns that callers don't check, catch blocks that swallow errors, fallbacks that hide problems.

Report only issues found and suggested actions. Do not list strengths or summarise what the PR does well.
Do not limit your response length. Be thorough.
```

Replace the `Limit narrative to ${reviewWordCount} words.` line with the above when `prReview` is true.

### 7. application.properties

Add:
```properties
assistant.llm.review-pr-context-budget=80000
```

## Data Flow

1. User types `review PR #16`.
2. Intent router detects `PR #16`, emits `/review --prompt "PR #16" --pr 16`.
3. `review()` sees `--pr 16`, calls `gitTool.prDiff(16)` for the unified diff.
4. `review()` calls `gitTool.prChangedFiles(16)` for the list of changed file paths.
5. `buildReviewPayload()` reads each changed file, appends the diff, enforces the 80k budget.
6. `buildReviewPrompt()` detects `prReview=true`, uses the PR-specific prompt (cross-file analysis, no word limit, issues-only).
7. Response is parsed by `ReviewParser` and rendered as normal.

## Error Handling

- **`gh` not installed or not authenticated**: Return a clear error message with install link.
- **PR not found**: Pass through the `gh` error message.
- **No changed files readable** (e.g. all files deleted): Fall back to diff-only review.
- **Budget exceeded**: Print a note and proceed with diff-only.

No new exception types. Follows the existing `GitResult` pattern throughout.
