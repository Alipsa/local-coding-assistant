# Local Coding Assistant Backlog

Goal: deliver a local-only CLI coding assistant (Spring Shell + Embabel) that uses Ollama models to edit, review, search, and interact with git repositories.

## 1) Shell and agent foundation
- [ ] Wire a default coding agent class under `src/main/java/se/alipsa/lca` with a system prompt tuned for local code assistance (deepseek-coder).
- [ ] Add a Spring Shell command (e.g., `chat`) that streams model output, supports multiline prompts, and preserves session state per invocation.
- [ ] Provide basic configuration flags (model name, temperature, max tokens) via application properties and command options.
- [ ] Add minimal tests for agent wiring and prompt construction.

## 2) Editing and patch application
- [ ] Implement a `edit` workflow: load file content, send intent + context to the agent, receive a diff/patch, and apply it safely (with backup/temp file).
- [ ] Support targeted edits (line ranges or symbol names) to limit context size.
- [ ] Add dry-run/confirm mode that shows the patch before applying.
- [ ] Cover patch application logic with unit tests on sample files.

## 3) Code review and suggestions
- [ ] Add a `review` command that sends selected files/diffs to the model and returns actionable findings.
- [ ] Include severity tagging and file/line references in the response formatting.
- [ ] Provide an option to review only staged changes (integrates with git status).
- [ ] Add tests for the review command formatting and inputs.

## 4) Code search and context building
- [ ] Integrate ripgrep-based search to gather relevant snippets for prompts (respect ignore files).
- [ ] Implement a `context` builder that deduplicates and truncates snippets to stay within token limits.
- [ ] Add a `search` command that returns matches with surrounding lines and easy copy-to-context output.
- [ ] Unit-test context assembly and truncation.

## 5) Git-aware operations
- [ ] Add commands for `status`, `diff`, and `apply` that the agent can call or chain into prompts.
- [ ] Support staging selected hunks/files after an edit command (with confirmation).
- [ ] Include a `commit-suggest` helper that drafts commit messages from staged diffs.
- [ ] Add lightweight tests for git command wrappers (mocked repo).

## 6) Command execution and diagnostics
- [ ] Provide a `run` command to execute project scripts/tests with captured logs and exit codes.
- [ ] Allow the agent to request runs (with a confirmation gate) and feed summarized output back into the conversation.
- [ ] Add timeouts and output truncation to avoid runaway executions.

## 7) Model and runtime controls
- [ ] Expose a `model` command to list available Ollama models and switch the active one at runtime.
- [ ] Add a health check that verifies connectivity to `spring.ai.ollama.base-url` before starting a session.
- [ ] Support automatic fallbacks to a smaller model if the default is unavailable.

## 8) UX polish and docs
- [ ] Add streaming progress indicators and clear sectioned output for edits/reviews/search.
- [ ] Document all commands, options, and expected workflows in `README.md` and `docs/`.
- [ ] Provide quickstart examples showing edit/review/search/git flows end-to-end.

## 9) Optional A2A interoperability
- [ ] If desired, add a profile/command to expose the agent over A2A while keeping inference on Ollama.
- [ ] Document the A2A usage caveats for users who want remote UI access but local models.
