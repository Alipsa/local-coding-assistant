# Release Notes for the local-coding-assistant

## Version 1.2.0, 2026-01-16
**Highlights**
- Migrated from Spring Shell to custom JLine REPL for better control and natural language interaction.
- Added Embabel 0.3.2 thinking/reasoning capabilities to chat and review commands.
- Implemented tool call parsing for LLM-agnostic function calling (writeFile, replace, deleteFile, runCommand).
- Enhanced /implement command with shell command execution and concise output format.
- Added user input coloring (light green) for improved readability.

**REPL & UX**
- Replaced Spring Shell with custom JLine-based REPL for tighter control over input handling and natural language processing.
- Added syntax highlighting for user input (light green) to distinguish from assistant output.
- Fixed /clear command to work with both slash and non-slash variants (/clear, /cls, clear, cls).
- Improved multi-line input handling in command parser to support quoted strings with newlines.
- Enhanced intent routing with smart multi-line handling and conversational context tracking for file references.
- Improved conversational language handling for more natural interactions.

**AI & Agent Improvements**
- Upgraded to Embabel 0.3.2 with thinking/reasoning support:
  - ChatAgent now supports --show-reasoning and --with-thinking flags to expose LLM reasoning chains.
  - ReviewAgent displays thinking output when using models with extended thinking capabilities.
  - Added ChatResponse wrapper to return both message and reasoning content.
- Added tool call parser for LLM-agnostic function calling:
  - writeFile(path, content) - create or overwrite files.
  - replace(path, old, new) - modify existing files.
  - deleteFile(path) - delete files.
  - runCommand(command) - execute shell commands (chmod, mkdir, mv, cp, etc.).
- Enhanced /implement command:
  - Made output more concise (removed verbose Plan/Implementation/Notes format).
  - Added shell command execution support via runCommand() tool.
  - Now responds with brief confirmations and tool execution results only.
- Added auto-save feature for code blocks in /chat command.
- Improved code search with case-insensitive option (-i flag) and better feedback.

**Web Search Standardization**
- Added Embabel InternetResource and InternetResources domain object support.
- WebSearchTool now provides:
  - searchAsInternetResources() - returns List<InternetResource>.
  - searchAsWebSearchResults() - returns WebSearchResults wrapper.
  - Backward compatibility maintained with existing search() methods.

**Bug Fixes**
- Fixed integration test failures caused by ChatResponse type mismatch in agent process execution.
- Fixed /implement command to properly handle ChatResponse return type.
- Fixed multi-line input breaking command parser when pasting error messages or prompts with newlines.
- Fixed batch mode integration tests by implementing BatchModeRunner.
- Fixed NPE in integration tests related to agent result type resolution.

**Documentation & Tooling**
- Added comprehensive documentation for Embabel 0.3.2 features and implementation opportunities.
- Updated AGENTS.md to be language-agnostic (supports any programming language).
- Enhanced shell scripts (lca launcher) with better error handling and compatibility.
- Improved test output formatting and readability.
- Added presentation materials and expanded docs.

**Dependencies**
- Upgraded to Embabel 0.3.2 (from 0.3.1).
- Various dependency updates for improved stability and performance.

**Breaking Changes**
- Removed @Action annotation from ChatAgent.respond() method - now only respondWithThinking() is the agent action.
- /implement command output format changed from verbose Plan/Implementation/Notes to concise confirmation + results.

## Version 1.1.0, 2025-12-28
**Highlights**
- Added natural‑language intent routing with /route, /intent-debug, and a first‑class /plan command.
- New direct shell mode (/! with /sh alias) for streaming command output and session context capture.
- Web search overhaul with HtmlUnit/Jsoup fetchers and session‑level configuration.

**CLI & UX**
- /help now lists slash commands alphabetically and includes /config options.
- /config expanded with intent, local-only, and web-search session settings.
- Command‑not‑found suggestions (“did you mean …”).
- Local‑only mode prompts to temporarily enable web search for the session.

**Intent Routing & Planning**
- Intent router pipeline and models (allowlist, confidence, debug preview).
- /plan produces structured, command‑oriented steps and supports REST usage.
- Routing disabled for explicit /command input, with /route for preview.

**Web Search**
- HtmlUnit + Jsoup fetchers with configurable fallback.
- Session overrides for fetcher selection or disablement.
- Improved search summarisation and tool output handling.

**Shell & Execution**
- /! (“bash mode”) runs immediately with streamed output; results appended to session context.
- /run kept for confirmed, logged execution with truncation controls.

**REST API**
- Added /plan and /route endpoints to mirror CLI workflows.
- Updated REST docs and examples.

**Packaging & Tooling**
- New CLI launch script (src/main/bin/lca) - POSIX-compatible for bash, zsh, sh, dash, etc.
- Additional documentation for commands, workflows, and intent routing.

**Dependencies / Platform**
- Spring Boot 3.5.7 and Embabel 0.3.1 updates.
- HtmlUnit and Jsoup added for web search support.

## Version 1.0.0, 2025-12-23
- Highlights: Added non-interactive batch mode, REST API parity, project-specific AGENTS.md guidance, a git-aware /tree command, and a full tutorial with examples.
- Batch Mode: CLI -c/--batch-file execution with safer confirmations (--yes/--assume-yes), clearer diagnostics and exit handling, plus example batch files and a helper script in docs/examples/.
- Security & Safety: .aiexclude enforcement across tools, command allow/deny policies, secret scanning for commit suggestions, optional SAST integration for reviews, stronger log sanitisation, and
  hardened REST/OIDC/rate limiting behaviour.
- REST & CLI Parity: /api/cli endpoints mirror shell commands with improved validation and safer defaults; non-interactive git push confirmation control added.
- Docs & Examples: New docs/tutorial.md, refreshed command/workflow docs, and a runnable sample project in examples/sample-project/.
- Breaking/Behaviour Changes: A2A interoperability removed to reduce complexity.

## Version 0.9.0, 2025-12-19
- Fixed resolveModel NPE risk by guarding case-insensitive comparisons when the desired model is null.
- Strengthened ModelRegistry caching concurrency: health caching now uses a single synchronized fetch/update to avoid redundant calls; listModels already double-checks with synchronized reads/writes.
- Hardened ModelRegistry concurrency: cache reads/writes for models and health now use double-check locking to avoid redundant parallel fetches, reducing duplicate Ollama calls under contention.
- Improved ModelRegistry resilience and performance:
  - Added null check for base URL, cache TTLs for models and health, and synchronized/volatile cache updates.
  - listModels now caches results with TTL, logs at info when serving stale cache after failures, and returns cached data only when available.
  - checkHealth caches health responses with a short TTL to reduce redundant calls.
  - Adjusted constructor signatures accordingly and updated usages.
  - Added targeted tests: ResolveModelSpec exercises resolveModel branches; ModelRegistrySpec now covers caching, failure fallbacks, and error handling.
- Reduced duplicate health/model lookups: chat, review, and commitSuggest now reuse a single model list per call (calling health only when the list is empty).
- Added a TTL cache to ModelRegistry model listings to avoid repeated Ollama requests; availability checks now use the cached list.
- Added focused tests: ResolveModelSpec covers resolveModel edge cases; ModelRegistrySpec covers caching/error paths.
- Tightened Ollama health checks to treat only 2xx as reachable, simplified health handling, and made model resolution case-insensitive while returning the actual matched casing. Fallback model config now treats empty values as “no fallback.” - Hardened model availability logic to avoid assuming availability when listings fail.
- Added ModelRegistrySpec covering health, listing, and availability behavior; updated tests to align with new model/fallback handling.
- Added Groovy JSON dependency for model listing.
-  Cleaned ModelRegistry test harness and ensured new behavior is covered.
- Added runtime model controls: new model CLI command to list/set session models with fallback awareness, and health command to check Ollama connectivity. Chat/review/commit flows now verify Ollama health and auto-fallback to the configured smaller model when the requested/default model isn’t available, with user-visible notes.
- Introduced ModelRegistry for Ollama health/model discovery, wired into session handling, and surfaced fallback model configuration (assistant.llm.fallback-model) plus JSON dependency.
- Enhanced CommandRunner documentation/logic, strengthened truncation handling, and expanded tests for edge cases (empty command, logging failures, timeout cleanup). Added broader ShellCommands tests for model/health behaviors and adjusted SessionState for fallback support.
- Added a groovy and ollama badge to the readme
- Use explicit getMessage() method calls per code review feedback
- Distinguish IOException sources in StreamCollector catch block
- Clarified CommandRunner’s behavior and tightened robustness: added documentation about bash -lc usage and caller validation, made log-path/process creation overridable for tests, and corrected the StreamCollector error handling bug (now flags truncation properly).
- Expanded CommandRunnerSpec coverage: checks truncation flag, empty-command validation, log-path failure handling, log header/footer content, and timeout cleanup via a fake process; updated imports accordingly.
-  Fixed constructor mismatch by threading the new CommandRunner stub through CodeSearchCommandSpec, importing the runner, and rebuilding the test setup
- Command execution and diagnostics -
  -  Provide a `run` command to execute project scripts/tests with captured logs and exit codes.
  - Allow the agent to request runs (with a confirmation gate) and feed summarized output back into the conversation. -
  - Add timeouts and output truncation to avoid runaway executions.
  - If the current directory is not a git repo - commands like `status`, `diff`, and `commit-suggest` should gracefully inform the user that git operations are unavailable.
  - Require Interactive Confirmation. The CLI should print the command: > Agent wants to run: 'rm -rf ./build'. Allow? [y/N/a]
- GitTool now uses UTF-8 for git process I/O, caches repo existence with a lock to avoid repeated checks, and uses a dedicated runGitNoCheck helper to prevent recursion. Added a note about per-thread usage.
- Added path traversal rejection tests and stageHunks error-path tests; extended stageFiles traversal coverage.
- Adjusted dirty warning comment; updated tests accordingly.
- Removed unnecessary import in GitToolSpec.
- Refactored isGitRepo to use the git command through the shared helpers without recursion; added an opt-out flag for repo checks in runGitWithInput.
- Clarified 1-based hunk indexing with a comment and made dirty/staged checks explicitly test for non-empty output.
- Updated warning message to neutral wording and added rationale comment for direct stdout.
- Neutralized dirty-state warning and generalized commit prompt to “current project.”
- GitTool now tolerates missing roots by falling back to normalized paths, and validatePath always returns a normalized relative path (no traversal leak).
- Test helper runGit now fails fast on non-zero exits.
- Added ShellCommands tests for git status/diff/apply/push flows, including confirmation behavior.
- Expanded GitToolSpec to cover multiple hunk staging, file staging, applyPatch check/cached, and push; tweaked expectations to avoid silent failures.
- Hardened src/main/groovy/se/alipsa/lca/tools/GitTool.groovy: constructor now tolerates missing project roots by falling back to normalized absolute path; validatePath always returns a normalized relative path (even for non-existent files) to avoid traversal; isGitRepo callers still safe.
- Updated src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy to reject non-1-based hunk indexes instead of silently ignoring them.
- Expanded tests in src/test/groovy/se/alipsa/lca/tools/GitToolSpec.groovy: cover staging multiple/out-of-order hunks, staging files, applyPatch check/cached behaviors, and push to a local bare remote.
- Ensured constructor overload compatibility remains intact via the GitTool resolver addition earlier.
- Introduced src/main/groovy/se/alipsa/lca/tools/GitTool.groovy to wrap git status/diff/apply/stage/push with path safety and repo detection.
- Extended src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy with /status, /diff, /git-apply, /stage, /commit-suggest, and /git-push, plus dirty-state warnings and guarded confirmations before applying patches or pushing.
-  Added Spock coverage for git wrappers and shell wiring in src/test/groovy/se/alipsa/lca/tools/GitToolSpec.groovy and src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy.
- Fixed constructor mismatch by adding a GitTool-aware overload with backward compatibility, routed legacy constructor usage through it, and added a safe project-root resolver. Updated CodeSearchCommandSpec to compile via the restored signature.

## Version 0.7.0 - Git-aware operations
- 7.1 Add commands for `status`, `diff`, and `apply` that the agent can call or chain into prompts.
- 7.2 Support staging selected hunks/files after an edit command (with confirmation).
- 7.3 Include a `commit-suggest` helper that drafts commit messages from staged diffs.
- 7.4 Add lightweight tests for git command wrappers (mocked repo).
- 7.5 Add a "Safety Valve".
  - The agent should not be allowed to git push without explicit user confirmation (Y/n/a).
  - A "Dirty State" warning. If the user asks for a refactor but the git tree is dirty, warn them: "You have uncommitted changes. Consider committing before I apply patches."

## Version 0.6.0 - Web search augmentation
- 6.1 Enhance `WebSearchTool` with headless mode options, timeouts, result limits, and basic HTML sanitization/snippet trimming.
- 6.2 Add a CLI/agent flag to enable/disable web search, plus offline fallback messaging.
- 6.3 Cache recent queries for reuse in the same session; allow selecting search provider if available.
- 6.4 Make web search outputs usable by the agent (structured snippets) and add tests for parsing/formatting.

## Version 0.5.0 - Code search and context building
- 5.1 Integrate ripgrep-like search (respect .gitignore) to gather snippets for prompts.
- 5.2 Implement a context packer that deduplicates and truncates snippets to stay within token limits.
- 5.3 Add a `search` command returning matches with surrounding lines, copy-to-context, and optional web search toggle.
- 5.4 Unit-test context assembly and truncation.
- 5.5 Create a Context Budget Manager to manage context limits effectively.
  - The Challenge: Context limits. qwen3-coder:30b has a 256K window, but performance degrades as it gets closer to the limit.
  - Before sending the prompt, calculate the token count of: System Prompt + User Prompt + File A + File B. If it exceeds the limit, auto-summarize or drop the least relevant file before hitting the API.

## Version 0.4.0 - Code review and suggestions, 2025-12-12
Code review and suggestions
- 4.1 Add a `review` command that feeds selected files/diffs into the agent with severity-tagged output.
- 4.2 Include file/line references and recommendations; allow staged-only or path-filtered reviews.
- 4.3 Persist review summaries to a log file for later recall.
- 4.4 Add tests for review formatting and inputs.
- 4.5 Avoid unified diffs for generation. Instead, use Search and Replace Blocks.
  - The Challenge: LLMs are notoriously bad at line numbers. If the agent says "Replace line 40-45", and the file changed since the context was loaded, you corrupt the file. Instead, ask the model to output:
   ```
    <<<<SEARCH 
    > original code block 
    > ==== 
    > new code block
    >>>>
  ```
  - Your FileEditingTool locates the unique original code block string and replaces it. This is far more robust than line numbers for local models.
- 4.6 Ensure the output is parsable.
  - If the agent outputs a review, try to parse it into an object (File, Line, Severity, Comment) so you can render it nicely in the CLI (e.g., using ANSI colors for High/Medium/Low severity).

## Version 0.3.0 - Editing and patch application, Spring Shell CLI bridge, 2025-12-11
- 2.1 [x] Add Spring Shell commands that wrap the agent (e.g., `/chat`, `/review`, `/edit`, `/search`) and stream responses.
- 2.2 [x] Support multiline input, conversation/session state, and optional system prompt overrides.
- 2.3 [x] Provide CLI flags for model/temperature/max tokens and persist last-used options per session.
- 2.4 [x] Add smoke tests for command wiring and basic flows.
- 2.5 [x] Implement a "slash command" for input. The Challenge: Pasting multiline code into a CLI is painful.
  - /paste: Enters a mode specifically for pasting large buffers without triggering execution on newlines.
  - /edit: Opens the user's default $EDITOR (vim/nano/code), lets them type the prompt there, and sends it to the agent upon save/close. This is infinitely better than typing prompts in the shell.
  - 
- 3.1 [x] Extend `FileEditingTool` to support diff/patch application with backups and conflict detection.
- 3.2 [x] Enable targeted edits (line ranges, symbols) and contextual prompts for the agent.
- 3.3 [x] Add dry-run/confirm and revert options in the CLI.
- 3.4 [x] Cover patch application logic with unit tests on sample files.

## Version 0.1.0 - Initial Release, 2025-12-10
- Created from the Embabel java template project and translated to groovy.
- Setup basic structure for a local coding assistant using Embabel and Spring Boot.
- Agent hardening and prompts
  - 1.1 [x] Refine `se.alipsa.lca.agent.CodingAssistantAgent` personas/prompts toward repo-aware coding assistance (beyond short snippets).
  - 1.2 [x] Ensure actions use injected `Ai` (controller currently passes null) and propagate model/temperature options from config.
  - 1.3 [x] Add guardrails for word counts and result formatting (code + review sections) with tests.
  - 1.4 [x] Document/validate parameters via properties (`snippetWordCount`, `reviewWordCount`, default model).
  - 1.5 [x] Fix the REST controller’s null Ai handling and update the REST tests to cover full flows.