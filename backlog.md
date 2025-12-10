# Local Coding Assistant Backlog

Goal: deliver a local-only CLI coding assistant (Spring Shell + Embabel) that uses Ollama models to edit, review, search, and interact with git repositories. Current state: a Groovy `CodingAssistantAgent` produces/reviews code snippets and proxies simple file edits and web search; REST controllers exist, but no CLI workflow or git/context tooling.

## 1) Agent hardening and prompts
- 1.1 [x] Refine `se.alipsa.lca.agent.CodingAssistantAgent` personas/prompts toward repo-aware coding assistance (beyond short snippets).
- 1.2 [x] Ensure actions use injected `Ai` (controller currently passes null) and propagate model/temperature options from config.
- 1.3 [x] Add guardrails for word counts and result formatting (code + review sections) with tests.
- 1.4 [x] Document/validate parameters via properties (`snippetWordCount`, `reviewWordCount`, default model).
- 1.5 [x] Fix the REST controller’s null Ai handling and update the REST tests to cover full flows.

### The "Persona" Problem
The Challenge: local ollama models are great at code completion but can be terse or hallucinate APIs in chat mode.
Enforce Structured Output (JSON or XML tags) for tool usage. Local models struggle to "decide" to call a tool unless the system prompt explicitly forces a specific output format.

- 1.6 [x] Implement System Prompt Templating. Don't just have one persona. 
  - Coder Mode: Strict, outputs code blocks only. 
  - Architect Mode: Verbose, explains reasoning. 
  - Reviewer Mode: Critical, looks for security flaws.

## 2) Spring Shell CLI bridge
- 2.1 [ ] Add Spring Shell commands that wrap the agent (e.g., `/chat`, `/review`, `/edit`, `/search`) and stream responses.
- 2.2 [ ] Support multiline input, conversation/session state, and optional system prompt overrides.
- 2.3 [ ] Provide CLI flags for model/temperature/max tokens and persist last-used options per session.
- 2.4 [ ] Add smoke tests for command wiring and basic flows.
- 2.5 [ ] Implement a "slash command" for input. The Challenge: Pasting multiline code into a CLI is painful. 
  - /paste: Enters a mode specifically for pasting large buffers without triggering execution on newlines. 
  - /edit: Opens the user's default $EDITOR (vim/nano/code), lets them type the prompt there, and sends it to the agent upon save/close. This is infinitely better than typing prompts in the shell.

## 3) Editing and patch application
- 3.1 [ ] Extend `FileEditingTool` to support diff/patch application with backups and conflict detection.
- 3.2 [ ] Enable targeted edits (line ranges, symbols) and contextual prompts for the agent.
- 3.3 [ ] Add dry-run/confirm and revert options in the CLI.
- 3.4 [ ] Cover patch application logic with unit tests on sample files.

## 4) Code review and suggestions
- 4.1 [ ] Add a `review` command that feeds selected files/diffs into the agent with severity-tagged output.
- 4.2 [ ] Include file/line references and recommendations; allow staged-only or path-filtered reviews.
- 4.3 [ ] Persist review summaries to a log file for later recall.
- 4.4 [ ] Add tests for review formatting and inputs.
- 4.5 [ ] Avoid unified diffs for generation. Instead, use Search and Replace Blocks.
  - The Challenge: LLMs are notoriously bad at line numbers. If the agent says "Replace line 40-45", and the file changed since the context was loaded, you corrupt the file. Instead, ask the model to output:
   ```
    <<<<SEARCH 
    > original code block 
    > ==== 
    > new code block
    >>>>
  ```
  - Your FileEditingTool locates the unique original code block string and replaces it. This is far more robust than line numbers for local models.
- 4.6 [ ] Ensure the output is parsable. 
  - If the agent outputs a review, try to parse it into an object (File, Line, Severity, Comment) so you can render it nicely in the CLI (e.g., using ANSI colors for High/Medium/Low severity).  

## 5) Code search and context building
- 5.1 [ ] Integrate ripgrep-based search (respect .gitignore) to gather snippets for prompts.
- 5.2 [ ] Implement a context packer that deduplicates and truncates snippets to stay within token limits.
- 5.3 [ ] Add a `search` command returning matches with surrounding lines, copy-to-context, and optional web search toggle.
- 5.4 [ ] Unit-test context assembly and truncation.
- 5.5 [ ] Create a Context Budget Manager to manage context limits effectively.
  - The Challenge: Context limits. DeepSeek 6.7b usually has a 16k or 32k window, but performance degrades as you fill it. 
  - Before sending the prompt, calculate the token count of: System Prompt + User Prompt + File A + File B. If it exceeds the limit, you must auto-summarize or drop the least relevant file before hitting the API.
  
## 6) Web search augmentation
- 6.1 [ ] Enhance `WebSearchTool` with headless mode options, timeouts, result limits, and basic HTML sanitization/snippet trimming.
- 6.2 [ ] Add a CLI/agent flag to enable/disable web search, plus offline fallback messaging.
- 6.3 [ ] Cache recent queries for reuse in the same session; allow selecting search provider if available.
- 6.4 [ ] Make web search outputs usable by the agent (structured snippets) and add tests for parsing/formatting.

## 7) Git-aware operations
- 7.1 [ ] Add commands for `status`, `diff`, and `apply` that the agent can call or chain into prompts.
- 7.2 [ ] Support staging selected hunks/files after an edit command (with confirmation).
- 7.3 [ ] Include a `commit-suggest` helper that drafts commit messages from staged diffs.
- 7.4 [ ] Add lightweight tests for git command wrappers (mocked repo).
- 7.5 [ ] Add a "Safety Valve".
  - The agent should never be allowed to git push without explicit user confirmation (Y/n).
  - A "Dirty State" warning. If the user asks for a refactor but the git tree is dirty, warn them: "You have uncommitted changes. I recommend committing before I apply patches."
   
## 8) Command execution and diagnostics
- 8.1 [ ] Provide a `run` command to execute project scripts/tests with captured logs and exit codes.
- 8.2 [ ] Allow the agent to request runs (with a confirmation gate) and feed summarized output back into the conversation.
- 8.3 [ ] Add timeouts and output truncation to avoid runaway executions.
- 8.4 [ ] If the current directory is not a git repo
  - commands like `status`, `diff`, and `commit-suggest` should gracefully inform the user that git operations are unavailable.
  - Require Interactive Confirmation. The CLI should print the command: > Agent wants to run: 'rm -rf ./build'. Allow? [y/N]

## 9) Model and runtime controls
- 9.1 [ ] Expose a `model` command to list available Ollama models and switch the active one at runtime.
- 9.2 [ ] Add a health check that verifies connectivity to `spring.ai.ollama.base-url` before starting a session.
- 9.3 [ ] Support automatic fallbacks to a smaller model if the default is unavailable.

## 10) UX polish and docs
- 10.1 [ ] Add streaming progress indicators and clear sectioned output for edits/reviews/search.
- 10.2 [ ] Document all commands, options, and expected workflows in `README.md` and `docs/`.
- 10.3 [ ] Provide quickstart examples showing edit/review/search/git flows end-to-end.

## 11) A2A (Agent-to-Agent Interoperability) interoperability
- 11.1 [ ] Add a profile/command to expose the agent over A2A while keeping inference on Ollama.
- 11.2 [ ] Document the A2A usage caveats for users who want remote UI access but local models.

## 12) The "Tree" Representation
Why: When a user asks "Where is the authentication logic?", the agent cannot see the file structure unless you provide it.
- 12.1 [ ] Create a tool that dumps the directory structure (respecting .gitignore) into the context so the agent can "see" the project layout.

## 13) Token Counting Utility
Why: You will hit ContextLengthExceeded errors frequently.
- 13.1 [ ] Integrate a Java implementation of a BPE tokenizer (compatible with Llama/DeepSeek) to count tokens locally in Java before sending requests to Ollama.

## 14) Rest integration
Make sure the REST interface can do all the things that the cli can do. Try to keep things DRY as there is quite a lot of
overlap between CLI, A2A, and REST functionality. 

## 15) Security Hardening

Security is a critical concern for a tool that reads, writes, and executes code. The following measures aim to protect the user's system, ensure data privacy, and promote the generation of secure code.

### 15.1 Operational Safeguards & Sandboxing

Prevent the agent from performing destructive or unintended actions on the local system.

- **15.1.1 [ ] Interactive Confirmation for Destructive Operations:** Implement a strict confirmation gate (`[y/N]`) for any command that modifies the file system (`rm`, `mv`), applies git changes (`git apply`, `git push`), or executes arbitrary scripts. This expands on items `7.5` and `8.4`.
- **15.1.2 [ ] Filesystem Access Control:** Strictly limit file I/O to the project directory. Implement and enforce an `.aiexclude` file (similar to `.gitignore`) to prevent the agent from accessing sensitive files like `.env`, `credentials`, `*.pem`, or IDE configuration files.
- **15.1.3 [ ] Command Execution Sandboxing:** When executing code or shell commands (`/run`), do so within a sandboxed environment (e.g., a Docker container) to isolate the process from the host system. The sandbox should have no network access by default unless explicitly requested.
- **15.1.4 [ ] Dependency Vulnerability Scanning:** When the agent suggests adding a new dependency (e.g., in `pom.xml` or `build.gradle`), automatically scan it for known vulnerabilities using tools like OWASP Dependency-Check or the `gradle-dependency-check` plugin. Warn the user if vulnerabilities are found.

### 15.2 Secure Coding & Review

Enhance the agent's ability to write and review code with security in mind.

- **15.2.1 [ ] Dedicated Security Persona:** Create a specialized "Security Reviewer" persona. This prompt will instruct the LLM to analyze code specifically for common vulnerabilities (e.g., OWASP Top 10), such as injection flaws, hardcoded secrets, insecure dependencies, and improper error handling.
- **15.2.2 [ ] Integrate Static Analysis (SAST):** Augment the `/review` command to run a lightweight, fast SAST tool (e.g., Semgrep with a default ruleset) in addition to the LLM-based review. The agent can then summarize the SAST findings, providing a more reliable analysis than an LLM alone.
- **15.2.3 [ ] Secret Detection:** Before committing code with `commit-suggest`, automatically scan the diff for hardcoded secrets (API keys, passwords, tokens). If a potential secret is found, warn the user and prevent the commit.

### 15.3 Application & API Security

Secure the LCA application itself, particularly its REST interface.

- **15.3.1 [ ] Secure the REST API:** The REST API should be disabled by default. If enabled, secure it using Spring Security.
    - Implement API Key authentication for machine-to-machine communication.
    - For user-facing scenarios, consider standard protocols like OAuth2/OIDC.
- **15.3.2 [ ] Enforce HTTPS:** Require TLS for all REST API communication. Provide guidance on generating self-signed certificates for local development.
- **15.3.3 [ ] Input Validation:** Apply rigorous input validation on all CLI arguments and API request payloads to prevent command injection and other parsing-related vulnerabilities.
- **15.3.4 [ ] User Management (Optional Service):** Re-evaluate the need for built-in user/role management. This is only necessary if the application is intended to be run as a shared, multi-tenant service. For a local-first CLI, OS-level user permissions are sufficient. If implemented, use Spring Security's `JdbcUserDetailsManager` with a robust password hashing scheme (e.g., bcrypt).

### 15.4 Data Privacy

Uphold the "local-first" promise and protect user data.

- **15.4.1 [ ] Guarantee Local-Only Operation:** Explicitly document and guarantee that no code, prompts, or project data is ever sent to a third-party cloud service. All processing happens locally via Ollama.
- **15.4.2 [ ] No Telemetry by Default:** Do not collect or transmit any usage data or telemetry without explicit, opt-in consent from the user.
- **15.4.3 [ ] Log Sanitization:** Review all logging statements to ensure they do not accidentally log sensitive content from files, prompts, or agent responses (e.g., API keys, personal information).

### 15.5 A2A (Agent-to-Agent) Security

When exposing the LCA for agent-to-agent communication, additional security layers are required to prevent unauthorized access and abuse.

- **15.5.1 [ ] A2A Disabled by Default:** Ensure the A2A endpoint is disabled by default and requires explicit user configuration to activate, inheriting the same principle as the general REST API (`15.3.1`).
- **15.5.2 [ ] Mutual TLS (mTLS) Authentication:** For server-to-server A2A communication, mandate mTLS. Both the client (calling agent) and the server (LCA) must present valid, trusted certificates to establish a connection, providing stronger, identity-based authentication than API keys alone.
- **15.5.3 [ ] Scoped Access Control:** Implement a scope-based authorization model for remote agents. Define granular permissions (e.g., `file:read`, `file:write`, `git:read`, `command:execute`) and require connecting agents to be granted specific scopes. A remote agent must operate under the principle of least privilege.
- **15.5.4 [ ] Rate Limiting and Throttling:** Protect the A2A endpoint from denial-of-service attacks or runaway clients by implementing strict rate limiting on incoming requests.
- **15.5.5 [ ] Audit Trail:** Maintain a detailed audit log of all operations initiated through the A2A interface, including the identity of the calling agent (from its certificate), the requested operation, and the outcome.
