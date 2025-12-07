# Local Coding Assistant Backlog

Goal: deliver a local-only CLI coding assistant (Spring Shell + Embabel) that uses Ollama models to edit, review, search, and interact with git repositories. Current state: a Groovy `CodingAssistantAgent` produces/reviews code snippets and proxies simple file edits and web search; REST controllers exist, but no CLI workflow or git/context tooling.

## 1) Agent hardening and prompts
- [ ] Refine `se.alipsa.lca.agent.CodingAssistantAgent` personas/prompts toward repo-aware coding assistance (beyond short snippets).
- [ ] Ensure actions use injected `Ai` (controller currently passes null) and propagate model/temperature options from config.
- [ ] Add guardrails for word counts and result formatting (code + review sections) with tests.
- [ ] Document/validate parameters via properties (`snippetWordCount`, `reviewWordCount`, default model).
- [ ] Fix the REST controller’s null Ai handling and update the REST tests to cover full flows.

### The "Persona" Problem
The Challenge: deepseek-coder is great at code completion but can be terse or hallucinate APIs in chat mode.
Enforce Structured Output (JSON or XML tags) for tool usage. Local models struggle to "decide" to call a tool unless the system prompt explicitly forces a specific output format.

- [ ] Implement System Prompt Templating. Don't just have one persona. 
  - Coder Mode: Strict, outputs code blocks only. 
  - Architect Mode: Verbose, explains reasoning. 
  - Reviewer Mode: Critical, looks for security flaws.

## 2) Spring Shell CLI bridge
- [ ] Add Spring Shell commands that wrap the agent (e.g., `/chat`, `/review`, `/edit`, `/search`) and stream responses.
- [ ] Support multiline input, conversation/session state, and optional system prompt overrides.
- [ ] Provide CLI flags for model/temperature/max tokens and persist last-used options per session.
- [ ] Add smoke tests for command wiring and basic flows.
- [ ] Implement a "slash command" for input. The Challenge: Pasting multiline code into a CLI is painful. 
  - /paste: Enters a mode specifically for pasting large buffers without triggering execution on newlines. 
  - /edit: Opens the user's default $EDITOR (vim/nano/code), lets them type the prompt there, and sends it to the agent upon save/close. This is infinitely better than typing prompts in the shell.

## 3) Editing and patch application
- [ ] Extend `FileEditingTool` to support diff/patch application with backups and conflict detection.
- [ ] Enable targeted edits (line ranges, symbols) and contextual prompts for the agent.
- [ ] Add dry-run/confirm and revert options in the CLI.
- [ ] Cover patch application logic with unit tests on sample files.

## 4) Code review and suggestions
- [ ] Add a `review` command that feeds selected files/diffs into the agent with severity-tagged output.
- [ ] Include file/line references and recommendations; allow staged-only or path-filtered reviews.
- [ ] Persist review summaries to a log file for later recall.
- [ ] Add tests for review formatting and inputs.
- [ ] Avoid unified diffs for generation. Instead, use Search and Replace Blocks.
  - The Challenge: LLMs are notoriously bad at line numbers. If the agent says "Replace line 40-45", and the file changed since the context was loaded, you corrupt the file. Instead, ask the model to output:
   ```
    <<<<SEARCH 
    > original code block 
    > ==== 
    > new code block
    >>>>
  ```
  - Your FileEditingTool locates the unique original code block string and replaces it. This is far more robust than line numbers for local models.
- [ ] Ensure the output is parsable. 
  - If the agent outputs a review, try to parse it into an object (File, Line, Severity, Comment) so you can render it nicely in the CLI (e.g., using ANSI colors for High/Medium/Low severity).  

## 5) Code search and context building
- [ ] Integrate ripgrep-based search (respect .gitignore) to gather snippets for prompts.
- [ ] Implement a context packer that deduplicates and truncates snippets to stay within token limits.
- [ ] Add a `search` command returning matches with surrounding lines, copy-to-context, and optional web search toggle.
- [ ] Unit-test context assembly and truncation.
- [ ] Create a Context Budget Manager to manage context limits effectively.
  - The Challenge: Context limits. DeepSeek 6.7b usually has a 16k or 32k window, but performance degrades as you fill it. 
  - Before sending the prompt, calculate the token count of: System Prompt + User Prompt + File A + File B. If it exceeds the limit, you must auto-summarize or drop the least relevant file before hitting the API.
  
## 6) Web search augmentation
- [ ] Enhance `WebSearchTool` with headless mode options, timeouts, result limits, and basic HTML sanitization/snippet trimming.
- [ ] Add a CLI/agent flag to enable/disable web search, plus offline fallback messaging.
- [ ] Cache recent queries for reuse in the same session; allow selecting search provider if available.
- [ ] Make web search outputs usable by the agent (structured snippets) and add tests for parsing/formatting.

## 7) Git-aware operations
 - [ ] Add commands for `status`, `diff`, and `apply` that the agent can call or chain into prompts.
 - [ ] Support staging selected hunks/files after an edit command (with confirmation).
 - [ ] Include a `commit-suggest` helper that drafts commit messages from staged diffs.
 - [ ] Add lightweight tests for git command wrappers (mocked repo).
 - [ ] Add a "Safety Valve".
   - The agent should never be allowed to git push without explicit user confirmation (Y/n).
   - A "Dirty State" warning. If the user asks for a refactor but the git tree is dirty, warn them: "You have uncommitted changes. I recommend committing before I apply patches."
   
## 8) Command execution and diagnostics
 - [ ] Provide a `run` command to execute project scripts/tests with captured logs and exit codes.
 - [ ] Allow the agent to request runs (with a confirmation gate) and feed summarized output back into the conversation.
 - [ ] Add timeouts and output truncation to avoid runaway executions.
 - [ ] If the current directory is not a git repo
   - commands like `status`, `diff`, and `commit-suggest` should gracefully inform the user that git operations are unavailable.
   - Require Interactive Confirmation. The CLI should print the command: > Agent wants to run: 'rm -rf ./build'. Allow? [y/N]

## 9) Model and runtime controls
 - [ ] Expose a `model` command to list available Ollama models and switch the active one at runtime.
 - [ ] Add a health check that verifies connectivity to `spring.ai.ollama.base-url` before starting a session.
 - [ ] Support automatic fallbacks to a smaller model if the default is unavailable.

## 10) UX polish and docs
 - [ ] Add streaming progress indicators and clear sectioned output for edits/reviews/search.
 - [ ] Document all commands, options, and expected workflows in `README.md` and `docs/`.
 - [ ] Provide quickstart examples showing edit/review/search/git flows end-to-end.

## 11) Optional A2A interoperability
 - [ ] If desired, add a profile/command to expose the agent over A2A while keeping inference on Ollama.
 - [ ] Document the A2A usage caveats for users who want remote UI access but local models.

## 12) The "Tree" Representation
Why: When a user asks "Where is the authentication logic?", the agent cannot see the file structure unless you provide it.
 - [ ] Create a tool that dumps the directory structure (respecting .gitignore) into the context so the agent can "see" the project layout.

## 13) Token Counting Utility
Why: You will hit ContextLengthExceeded errors frequently.
 - [ ] Integrate a Java implementation of a BPE tokenizer (compatible with Llama/DeepSeek) to count tokens locally in Java before sending requests to Ollama.