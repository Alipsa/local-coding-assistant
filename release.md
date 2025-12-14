# Release Notes for the local-coding-assistant 

## Version 0.7.0 - Git-aware operations
- 7.1 Add commands for `status`, `diff`, and `apply` that the agent can call or chain into prompts.
- 7.2 Support staging selected hunks/files after an edit command (with confirmation).
- 7.3 Include a `commit-suggest` helper that drafts commit messages from staged diffs.
- 7.4 Add lightweight tests for git command wrappers (mocked repo).
- 7.5 Add a "Safety Valve".
  - The agent should not be allowed to git push without explicit user confirmation (Y/n/a).
  - A "Dirty State" warning. If the user asks for a refactor but the git tree is dirty, warn them: "You have uncommitted changes. I recommend committing before I apply patches."

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