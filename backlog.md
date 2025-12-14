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
- 2.1 [x] Add Spring Shell commands that wrap the agent (e.g., `/chat`, `/review`, `/edit`, `/search`) and stream responses.
- 2.2 [x] Support multiline input, conversation/session state, and optional system prompt overrides.
- 2.3 [x] Provide CLI flags for model/temperature/max tokens and persist last-used options per session.
- 2.4 [x] Add smoke tests for command wiring and basic flows.
- 2.5 [x] Implement a "slash command" for input. The Challenge: Pasting multiline code into a CLI is painful. 
  - /paste: Enters a mode specifically for pasting large buffers without triggering execution on newlines. 
  - /edit: Opens the user's default $EDITOR (vim/nano/code), lets them type the prompt there, and sends it to the agent upon save/close. This is infinitely better than typing prompts in the shell.

## 3) Editing and patch application
- 3.1 [x] Extend `FileEditingTool` to support diff/patch application with backups and conflict detection.
- 3.2 [x] Enable targeted edits (line ranges, symbols) and contextual prompts for the agent.
- 3.3 [x] Add dry-run/confirm and revert options in the CLI.
- 3.4 [x] Cover patch application logic with unit tests on sample files.

## 4) Code review and suggestions
- 4.1 [x] Add a `review` command that feeds selected files/diffs into the agent with severity-tagged output.
- 4.2 [x] Include file/line references and recommendations; allow staged-only or path-filtered reviews.
- 4.3 [x] Persist review summaries to a log file for later recall.
- 4.4 [x] Add tests for review formatting and inputs.
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
- 4.5 [x] Avoid unified diffs for generation. Instead, use Search and Replace Blocks.
- 4.6 [x] Ensure the output is parsable. 
  - If the agent outputs a review, try to parse it into an object (File, Line, Severity, Comment) so you can render it nicely in the CLI (e.g., using ANSI colors for High/Medium/Low severity).  

## 5) Code search and context building
- 5.1 [x] Integrate ripgrep-like search (respect .gitignore) to gather snippets for prompts.
- 5.2 [x] Implement a context packer that deduplicates and truncates snippets to stay within token limits.
- 5.3 [x] Add a `search` command returning matches with surrounding lines, copy-to-context, and optional web search toggle.
- 5.4 [x] Unit-test context assembly and truncation.
- 5.5 [x] Create a Context Budget Manager to manage context limits effectively.
  - The Challenge: Context limits. qwen3-coder:30b has a 256K window, but performance degrades as you fill it. 
  - Before sending the prompt, calculate the token count of: System Prompt + User Prompt + File A + File B. If it exceeds the limit, you must auto-summarize or drop the least relevant file before hitting the API.
  
## 6) Web search augmentation
- 6.1 [x] Enhance `WebSearchTool` with headless mode options, timeouts, result limits, and basic HTML sanitization/snippet trimming.
- 6.2 [x] Add a CLI/agent flag to enable/disable web search, plus offline fallback messaging.
- 6.3 [x] Cache recent queries for reuse in the same session; allow selecting search provider if available.
- 6.4 [x] Make web search outputs usable by the agent (structured snippets) and add tests for parsing/formatting.

## 7) Git-aware operations
- 7.1 [x] Add commands for `status`, `diff`, and `apply` that the agent can call or chain into prompts.
- 7.2 [x] Support staging selected hunks/files after an edit command (with confirmation).
- 7.3 [x] Include a `commit-suggest` helper that drafts commit messages from staged diffs.
- 7.4 [x] Add lightweight tests for git command wrappers (mocked repo).
- 7.5 [x] Add a "Safety Valve".
  - The agent should not be allowed to git push without explicit user confirmation (Y/n/a).
  - A "Dirty State" warning. If the user asks for a refactor but the git tree is dirty, warn them: "You have uncommitted changes. I recommend committing before I apply patches."
   
## 8) Command execution and diagnostics
- 8.1 [ ] Provide a `run` command to execute project scripts/tests with captured logs and exit codes.
- 8.2 [ ] Allow the agent to request runs (with a confirmation gate) and feed summarized output back into the conversation.
- 8.3 [ ] Add timeouts and output truncation to avoid runaway executions.
- 8.4 [ ] If the current directory is not a git repo
  - commands like `status`, `diff`, and `commit-suggest` should gracefully inform the user that git operations are unavailable.
  - Require Interactive Confirmation. The CLI should print the command: > Agent wants to run: 'rm -rf ./build'. Allow? [y/N/a]

## 9) Model and runtime controls
- 9.1 [ ] Expose a `model` command to list available Ollama models and switch the active one at runtime.
- 9.2 [ ] Add a health check that verifies connectivity to `spring.ai.ollama.base-url` before starting a session.
- 9.3 [ ] Support automatic fallbacks to a smaller model if the default is unavailable.

## 10) UX polish and docs
- 10.1 [ ] Add streaming progress indicators and clear sectioned output for edits/reviews/search.
- 10.2 [ ] Document all commands, options, and expected workflows in `README.md` and `docs/`.
- 10.3 [ ] Provide quickstart examples showing edit/review/search/git flows end-to-end.

## 11) Remove A2A (Agent-to-Agent Interoperability)

- 11.1 [ ] A2A is not needed for this application and should be removed to reduce complexity.
  Remove all A2A-related code, documentation, and configuration from the project
  to streamline the codebase and focus on single-agent functionality.

## 12) The "Tree" Representation
Why: When a user asks "Where is the authentication logic?", the agent cannot see the file structure unless you provide it.
- 12.1 [ ] Create a tool that dumps the directory structure (respecting .gitignore) into the context so the agent can "see" the project layout.

## 13) REST integration
Make sure the REST interface can do all the things that the cli can do. Try to keep things DRY as there is quite a lot of
overlap between CLI and REST functionality. 

## 14) Security Hardening

Security is a critical concern for a tool that reads, writes, and executes code.
The measures below aim to:

- Prevent destructive or unintended changes to the system.
- Protect project data and secrets.
- Promote the generation of secure code.
- Avoid accidentally exposing a powerful local tool over the network.

### 14.1 Operational Safeguards

Prevent the agent from performing destructive or unintended actions on the local system.

- **14.1.1 [ ] Interactive Confirmation for Destructive Operations**  
  Implement a strict confirmation gate (`[y/N/a]`) for any command that:
  - Modifies the file system in a destructive way (`rm`, `mv`, `cp -r`, etc.),
  - Applies git changes (`git apply`, `git commit`, `git push`),
  - Executes arbitrary scripts or project commands (`/run`, `mvn`, `gradle`, `npm`, etc.).

  This builds on items `7.5` and `8.4`: the agent can *propose* actions, but the user must confirm them.

- **14.1.2 [ ] Filesystem Access Control**  
  Strictly limit file I/O to the current project root (or a configured workspace root).  
  Add support for an `.aiexclude` file (similar to `.gitignore`) to prevent the agent from
  reading or touching sensitive files such as:
  - `.env`, `*.pem`, `id_rsa`, `credentials.*`
  - `~/.ssh`, keychains, OS config directories
  - IDE config files or any path explicitly excluded by the user.

- **14.1.3 [ ] Command Execution Safety**  
  For the `/run` and other execution-related commands:
  - Start by enforcing:
    - Explicit confirmation before execution.
    - A configurable allowlist/denylist of commands.
    - Timeouts and output truncation (see `8.3`).

- **14.1.4 [ ] Dependency Vulnerability Scanning (Later / Optional)**  
  When the agent suggests adding a new dependency (e.g., to `pom.xml` or `build.gradle`),
  integrate with a vulnerability scanner (e.g., OWASP Dependency-Check or a Gradle plugin)
  to check for known issues. If vulnerabilities are detected, show a clear warning and
  include them in the review output. This is a nice-to-have enhancement rather than a
  requirement for a first usable version.

### 14.2 Secure Coding & Review

Improve the agent’s ability to write and review code with security in mind.

- **14.2.1 [ ] Dedicated Security Persona**  
  Add a specialized "Security Reviewer" persona/prompt that focuses on:
  - Common vulnerabilities (OWASP Top 10 style): injection, insecure deserialization,
    hardcoded secrets, insecure crypto, etc.
  - Misconfigurations in frameworks and libraries (e.g., default credentials, wide-open CORS).
  - Inadequate error handling or logging of sensitive data.

  The CLI should expose this mode via a flag (e.g., `review --security`).

- **14.2.2 [ ] Integrate Static Analysis (SAST) [Optional / pluggable]**  
  Enhance the `/review` command with an optional static analysis step (e.g., Semgrep with
  a default ruleset):
  - Run the SAST tool on the files/diffs under review.
  - Parse and summarize findings (rules, severity, locations).
  - Present them together with the LLM-based review for better coverage.

- **14.2.3 [ ] Secret Detection Before Commit**  
  Before running `commit-suggest` or any commit helper:
  - Scan the staged diff for likely secrets (API keys, tokens, passwords).
  - If a potential secret is detected:
    - Warn the user clearly.
    - Require an explicit override to proceed.
    - Recommend removing or rotating the secret as appropriate.

### 14.3 Application & API Security

#### 14.3.1 Local vs Remote Access Modes

Define clear modes for how the REST API is exposed:

- **Local-only REST Mode (default)**
  - REST is disabled by default.
  - When enabled without extra configuration:
    - Bind only to `127.0.0.1`.
    - No separate API authentication; OS user identity is assumed.
    - Capabilities mirror those of the CLI (including confirmation gates from 14.1.1).
  - This treats REST calls from the local machine as equivalent to CLI usage.

- **Remote REST Mode (opt-in)**
  - Expose the API on `0.0.0.0` or a specific network interface.
  - Require explicit configuration to enable.
  - Must enforce HTTPS and authentication (see 14.3.2–14.3.4).
  - Intended for:
    - Remote UIs.
    - Any scenario where calls originate off the host machine.

#### 14.3.2 Authentication & Authorization (REST)

- **14.3.2.1 [ ] API Key Authentication**  
  For machine-to-machine usage (e.g., remote tools or scripts), support simple API key
  authentication with:
  - Keys stored in a configuration file or environment variable.
  - The ability to generate/revoke keys.

- **14.3.2.2 [ ] OAuth2/OIDC (Optional)**  
  For interactive, user-facing remote UIs, consider integrating OAuth2/OIDC via Spring Security
  so that:
  - Users authenticate through a standard identity provider.
  - Roles/authorities can be mapped to fine-grained permissions.

- **14.3.2.3 [ ] Scope-Based Authorization**  
  Implement a scope or permission model that can be applied both to API keys and OIDC users:
  - Example scopes:
    - `file:read`, `file:write`
    - `git:read`, `git:write`
    - `command:execute`
  - Enforce the principle of least privilege for remote callers.

#### 14.3.3 Transport Security (TLS)

- **14.3.3.1 [ ] Enforce HTTPS for Remote Mode**
  - Require TLS for all REST traffic when running in remote mode.
  - Provide documentation and helper scripts for generating self-signed certificates
    for local dev and testing.

#### 14.3.4 Protection Against Abuse

- **14.3.4.1 [ ] Rate Limiting & Throttling**  
  Implement rate limiting for remote requests to prevent accidental or malicious
  denial-of-service scenarios.

- **14.3.4.2 [ ] Audit Logging for Remote Operations**  
  Maintain an audit log for all operations invoked via remote REST:
  - Caller identity (API key or authenticated user).
  - Operation type (file read/write, git, run).
  - Timestamps and outcome (success/failure).

#### 14.3.5 Input Validation

- **14.3.5.1 [ ] Validate CLI & API Inputs**  
  Apply rigorous validation and sanitization to:
  - CLI arguments (paths, command names, flags).
  - REST request payloads and query parameters.
  - Any input that could be used to build shell commands or file paths.

  The goal is to avoid command injection, path traversal, and similar issues.

- **14.3.5.2 [ ] User Management Considerations**  
  For local CLI and local-only REST, OS-level user permissions are sufficient.  
  For remote REST:
  - Rely on Spring Security’s user/role model if user-level auth is needed.
  - Use robust password hashing (e.g., bcrypt) and standard security practices
    if you implement username/password logins.

### 14.4 Data Privacy

Uphold the "local-first" promise and protect user data.

- **14.4.1 [ ] Guarantee Local-Only Operation**  
  Explicitly document and test that:
  - No code, prompts, project data, or metadata is sent to third-party cloud services.
  - All inference happens via locally-running Ollama models or other local runtimes.

- **14.4.2 [ ] No Telemetry by Default**  
  Do not collect or transmit any usage data or telemetry without explicit user opt-in.  
  If telemetry is ever added:
  - Make it disabled by default.
  - Document exactly what is collected.
  - Provide a clear configuration toggle.

- **14.4.3 [ ] Log Sanitization**  
  Review logging across the project to ensure that:
  - Logs do not include large code blocks, prompt bodies, or agent responses by default.
  - Logs explicitly avoid storing secrets or sensitive content.
  - Where detailed logs are needed (e.g., debug mode), warn the user that logs may
    contain code and should be handled accordingly.

## 15) Batch mode / non-interactive execution

Why: Enable scripted use and CI-style end-to-end tests by running a sequence of commands non-interactively and then exiting. Example:

```bash
java -jar local-coding-assistant-0.2.0-SNAPSHOT.jar \
  -c "status; review --paths src/main/java; commit-suggest"
```

### 15.1 CLI flag and mode selection

* **15.1.1 [ ] Add a `-c` / `--command` option**

  * Accept a single string containing one or more CLI commands.
  * Presence of `-c/--command` switches the app into *batch mode*:

    * No interactive prompt.
    * Execute the supplied commands.
    * Exit when done.

* **15.1.2 [ ] Add `--batch-file` for script files**

  * Accept a path to a file containing one command per line (or semicolon-separated blocks).
  * Treat `--batch-file` as mutually exclusive with `-c/--command`.
  * Consider supporting `--batch-file -` to read from stdin for shell pipelines.

* **15.1.3 [ ] Keep interactive mode as default**

  * If neither `-c` nor `--batch-file` is present, start the existing Spring Shell interactive session as today.

### 15.2 Command parsing and execution semantics

* **15.2.1 [ ] Command separator rules**

  * Support `;` as a command separator in the `-c` string.

    * Example: `"status; review --paths src/main/java; run mvn test"`.
  * Trim whitespace around each command.
  * Ignore empty segments (e.g., consecutive `;;` or trailing semicolon).

* **15.2.2 [ ] Quoting and escaping**

  * Clearly define how quoting works inside `-c`:

    * Rely on the *shell* to handle outer quotes.
    * Inside the `-c` string, treat `"` and `'` just as the Spring Shell parser does for normal commands.
  * Document any limitations (e.g., no way to include a literal `;` unless escaped or quoted).

* **15.2.3 [ ] Execution order & failure handling**

  * Execute commands sequentially in the order given.
  * On failure:

    * Decide a policy:

      * Option A (safer default): Stop on first failure and exit.
      * Option B: Continue executing remaining commands but track failures.
  * Implement the chosen policy consistently and document it.

### 15.3 Exit codes and testability

* **15.3.1 [ ] Exit code contract**

  * Define how the process exit code is derived in batch mode:

    * `0` if all commands succeed.
    * Non-zero if any command fails (e.g., use the first failing command’s code or a generic `1`).
  * Make this explicit in the README for CI use.

* **15.3.2 [ ] Per-command status reporting**

  * Print a short status line before/after each command, e.g.:

    ```text
    > status
    [OK] status
    > review --paths src/main/java
    [ERROR] review --paths src/main/java (see above)
    ```
  * Ensure messages are stable enough that integration tests can parse them reliably (even if they don’t parse full output).

### 15.4 Output formatting for batch mode

* **15.4.1 [ ] Human-readable default output**

  * Keep the normal, nicely formatted output for human usage, including streaming where available.

* **15.4.2 [ ] Optional machine-friendly mode (for tests)**

  * Add a flag like `--batch-json` or `--batch-compact` to:

    * Wrap each command’s result in a simple structured envelope (e.g., JSONL or clearly delimited blocks).
    * Include at minimum:

      * Command string.
      * Start/end timestamps.
      * Success/failure.
      * Short summary message.
  * This is primarily for end-to-end integration tests that want predictable parsing.

### 15.5 Interaction with confirmations and safety gates

* **15.5.1 [ ] Respect existing confirmation prompts**

  * Batch mode must still honor confirmation gates from:

    * Destructive filesystem operations.
    * Git operations (`apply`, `commit`, `push`).
    * Command execution (`run`, `mvn`, `gradle`, `npm`, etc.).
  * Default behavior in batch mode:

    * If a command requires confirmation and none is provided, the command should fail cleanly rather than silently proceeding.

* **15.5.2 [ ] Non-interactive confirmation override for CI**

  * Add a flag like `--yes` / `--assume-yes` that:

    * Auto-answers “yes” to `[y/N/a]` prompts.
    * Is **off by default** for safety.
  * For dangerous operations (e.g. `git push`), consider requiring both:

    * `--yes` and
    * A command-specific `--force`/`--allow-push` flag (or similar), to avoid accidental misuse.

### 15.6 Session configuration and environment

* **16.6.1 [ ] Reuse the same configuration as interactive mode**

  * Batch mode should:

    * Respect the same config files (model, temperature, default paths, workspace root, `.aiexclude`, etc.).
    * Respect the same security limits (workspace root, deny-listed commands).

* **15.6.2 [ ] Working directory semantics**

  * Document that all commands in batch mode execute relative to the process working directory (`$PWD`) where `java -jar` is invoked.
  * If the current directory is not a git repo, commands like `status`/`diff`/`commit-suggest` should behave as specified in 8.4 (graceful “git unavailable” messages).

### 15.7 Error handling and diagnostics

* **15.7.1 [ ] Clear error reporting**

  * If parsing the `-c` string fails (e.g., invalid Spring Shell command), print a clear error and exit non-zero.
  * If `--batch-file` cannot be read, print a clear error and exit non-zero.

* **15.7.2 [ ] Logging considerations**

  * Ensure batch mode logging:

    * Does not spam logs with massive prompts/responses by default (respect 14.4.3).
    * Is sufficient to debug failures in CI (e.g., log which command failed and why).

### 15.8 Integration and tests

* **15.8.1 [ ] Wire batch mode into application startup**

  * On startup:

    * Parse CLI args.
    * If `-c` or `--batch-file` is present, execute batch mode logic and exit *before* starting the interactive Spring Shell loop.
    * Otherwise, proceed with interactive mode as today.

* **15.8.2 [ ] End-to-end integration tests**

  * Add tests that:

    * Launch the app (e.g., with `spring-boot:run` or via the jar) in batch mode.
    * Supply simple command sequences like:

      * `"status"`
      * `"search --query Foo; review --paths src/main/java"`
    * Assert on:

      * Exit codes.
      * Presence of key output markers.
      * Expected file/git side effects in a temporary test repo.

* **15.8.3 [ ] Document batch mode usage**

  * Update `README.md` and `docs/` with:

    * Examples for:

      * Simple one-liner.
      * Semicolon-separated sequence.
      * `--batch-file` usage.
      * CI/automation scenarios.
    * Explanation of exit codes and confirmation behavior.
    * Safety cautions when combining `--yes` with destructive commands.
