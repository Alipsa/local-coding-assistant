![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Groovy](https://img.shields.io/badge/groovy-4298B8.svg?style=for-the-badge&logo=apachegroovy&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge&logo=spring&logoColor=white)
![Ollama](https://img.shields.io/badge/ollama-000000.svg?style=for-the-badge&logo=ollama&logoColor=white)
[![Maven Central][maven-badge]][maven-link]
[![Javadoc][javadoc-badge]][javadoc-link]

[maven-badge]: https://img.shields.io/maven-central/v/se.alipsa.lca/local-coding-assistant.svg?style=for-the-badge
[maven-link]: https://search.maven.org/artifact/se.alipsa.lca/local-coding-assistant
[javadoc-badge]: https://javadoc.io/badge2/se.alipsa.lca/local-coding-assistant/javadoc.svg?style=for-the-badge
[javadoc-link]: https://javadoc.io/doc/se.alipsa.lca/local-coding-assistant

# Local Coding Assistant

Local-first coding assistant that runs on your machine and talks only to Ollama-served models. The goal is to deliver a CLI experience with editing, review, search, and git-aware tools—similar to ChatGPT Codex, Gemini CLI, and Claude Code — without any cloud dependency. Embabel provides the agent runtime, Spring Boot hosts it, and Spring Shell exposes the commands.

## What this project is today
- Spring Boot entry point with Embabel agents enabled
  (`src/main/java/se/alipsa/lca/LocalCodingAssistantApplication.java`).
- Ollama-first configuration using `qwen3-coder:30b` (see `src/main/resources/application.properties`).
- Helper scripts for launching the shell (`scripts/shell.sh`) and installing a DeepSeek model locally (`deepseek.sh`).
- Documentation stub for Ollama setup (`docs/llm-docs.md`).

## Getting started
1. Install Java 21 and ensure the Ollama daemon is running.
2. Pull a code-capable model. `./models.sh` will install the appropriate models.
3. Start the interactive shell: `./scripts/shell.sh`. The script sets `AGENT_APPLICATION` and launches Spring Shell
   with Embabel agents loaded.
4. Begin adding agents and tools under `src/main/java/se/alipsa/lca` to shape the coding workflows
   (editing, reviewing, searching, git operations).

See [Quickstart](docs/quickstart.md) for more details.

## Tutorial
Use the step-by-step walkthrough in `docs/tutorial.md`, including batch mode examples in `docs/examples/`.

## Configuration
- Update `spring.ai.ollama.base-url` and `embabel.models.default-llm` in
  `src/main/resources/application.properties` to point at your Ollama host and preferred model.
- Tune agent behavior via:
  - `assistant.llm.model`, `assistant.llm.temperature.craft`, `assistant.llm.temperature.review`
  - `assistant.llm.max-tokens`, `assistant.system-prompt`
  - `snippetWordCount`, `reviewWordCount`

## Safety
- Add a `.aiexclude` file at the project root to block the assistant from reading or modifying sensitive files.
  Patterns are glob-like and applied relative to the repo (for example: `.env`, `*.pem`, `credentials.*`, `build/`).
- Add an `AGENTS.md` file at the project root to define project-specific agent guidance
  (see `docs/agents.md` for format and usage).
- Configure command execution policy with `assistant.command.allowlist` and `assistant.command.denylist`
  in `src/main/resources/application.properties` (comma-separated prefixes like `mvn*,git*`).
- Local-only mode (`assistant.local-only=true`) disables remote REST access and web search unless you opt in.
- REST access is local-only by default; configure `assistant.rest.remote.enabled`, `assistant.rest.api-key`,
  `assistant.rest.require-https`, and `assistant.rest.rate-limit.per-minute` in
  `src/main/resources/application.properties` if needed. Optional OIDC settings live under
  `assistant.rest.oidc.*`, and scope enforcement uses `assistant.rest.scope.read` / `assistant.rest.scope.write`.
- Optional static analysis for reviews uses `assistant.sast.command` (for example:
  `semgrep --config auto --json {paths}`).
- No telemetry is collected or sent; logs are sanitized to redact common secrets.

## Commands overview
Detailed command documentation lives in `docs/commands.md`, with workflows in `docs/workflows.md`.
REST usage is documented in `docs/rest.md`.
Plain text input is routed into commands when intent routing is enabled; otherwise it maps to
`/chat --prompt "<text>"`.

Intent routing configuration lives in `src/main/resources/application.properties`:
```
assistant.intent.enabled=true
assistant.intent.model=tinyllama
assistant.intent.fallback-model=gpt-oss:20b
assistant.intent.temperature=0.0
assistant.intent.max-tokens=256
assistant.intent.allowed-commands=/chat,/plan,/review,/edit,/apply,/run,/gitapply,/git-push,/search
assistant.intent.destructive-commands=/edit,/apply,/run,/gitapply,/git-push
assistant.intent.confidence-threshold=0.8
```

- `/chat`: Send prompts. Options: `--persona`, `--session`, `--model`, `--temperature`,
  `--review-temperature`, `--max-tokens`, `--system-prompt`.
- `/config`: Update session settings (auto-paste, local-only, web-search, intent routing).
- `/plan`: Generate a numbered plan of CLI commands. Options: `--persona`, `--session`, `--model`,
  `--temperature`, `--review-temperature`, `--max-tokens`, `--system-prompt`.
- `/route`: Preview intent routing output without executing commands.
- `/intent-debug`: Print routing JSON and planned commands without executing.
- `/review`: Review code with structured Findings/Tests output. Options: `--paths`, `--staged`,
  `--min-severity`, `--no-color`, `--log-review`, `--security`, `--sast`, plus model/temperature overrides.
- `/reviewlog`: Show recent review entries. Options: `--min-severity`, `--path-filter`, `--limit`,
  `--page`, `--since`, `--no-color`.
- `/search`: Web search through the agent tool. Options: `--limit`, `--provider`, `--timeout-millis`,
  `--headless`, `--enable-web-search`.
- `/codesearch`: Ripgrep-backed repo search. Options: `--paths`, `--context`, `--limit`, `--pack`,
  `--max-chars`, `--max-tokens`.
- `/edit`: Open `$EDITOR` to draft prompts. Options: `--seed`, `--send`, `--session`, `--persona`.
- `/paste`: Paste multiline input (end with `/end`). Options: `--content`, `--end-marker`, `--send`,
  `--session`, `--persona`.
- `/status`: Git status. Options: `--short-format`.
- `/diff`: Git diff. Options: `--staged`, `--context`, `--paths`, `--stat`.
- `/gitapply`: Apply a patch via git. Options: `--patch-file`, `--cached`, `--check`, `--confirm`.
- `/stage`: Stage files or hunks. Options: `--paths`, `--file`, `--hunks`, `--confirm`.
- `/commit-suggest`: Draft a commit message. Options: `--session`, `--model`, `--temperature`,
  `--max-tokens`, `--hint`, `--secret-scan`, `--allow-secrets`.
- `/help`: Show available slash commands and `/config` options.
- `/git-push`: Push with confirmation. Options: `--force`.
- `/model`: List or set models. Options: `--list`, `--set`, `--session`.
- `/health`: Check Ollama connectivity.
- `/!`: Execute a shell command directly (alias: `/sh`). Output streams live and a summary is added to
  the session context.
- `/run`: Execute a command with timeout and truncation. Options: `--timeout-millis`, `--max-output-chars`,
  `--confirm`, `--agent-requested`.
- `/apply`: Apply unified diff patches. Options: `--patch-file`, `--dry-run`, `--confirm`.
- `/applyBlocks`: Apply Search-and-Replace blocks. Options: `--blocks`, `--blocks-file`, `--dry-run`,
  `--confirm`.
- `/revert`: Restore from the latest patch backup. Options: `--dry-run`.
- `/context`: Show targeted edit context. Options: `--start`, `--end`, `--symbol`, `--padding`.
- `/tree`: Show repository tree (respects `.gitignore`). Options: `--depth`, `--dirs-only`, `--max-entries`.

## Quickstart examples
Full end-to-end flows are in `docs/quickstart.md`.

Edit flow:
```
/context --file-path src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
  --symbol review
/chat --prompt "Update the review output header to include timestamps."
/applyBlocks --file-path src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy \
  --blocks "<blocks>"
```

Review flow:
```
/review --paths src/main/groovy --prompt "Look for error handling and logging gaps"
/reviewlog --min-severity MEDIUM --limit 3
```

Plan flow:
```
/plan --prompt "Review src/main/groovy and suggest improvements"
```

Search + git flow:
```
/codesearch --query "applyPatch" --paths src/main/groovy
/diff --staged
/stage --paths src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy
/commit-suggest --hint "UX polish"
```

## Batch mode
Run one or more commands non-interactively and exit when done.

Inline command string:
```
java -jar local-coding-assistant-<version>.jar \
  -c "/status; /review --paths src/main/groovy; /commit-suggest"
```

Batch file (one command per line; each line can include `;`-separated commands):
```
java -jar local-coding-assistant-<version>.jar --batch-file scripts/batch.txt
```

Notes:
- Exit code is `0` when all commands succeed, non-zero on the first failure.
- Use `--yes` (or `--assume-yes`) to auto-confirm destructive prompts in CI; leave it off by default.
- Add `--batch-json` to emit a JSON summary line per command for machine parsing.
- Batch mode uses the same configuration files, workspace root, and safety limits as interactive mode.
- Commands run relative to the working directory where `java -jar` starts; if that is not a git repo,
  git commands report that git is unavailable.

## Roadmap focus
- Rich editing and review loops driven from the CLI.
- Code search and repository-aware context with git operations.
- Opinionated prompts and workflows tuned for local development sessions.

## License

MIT License. See `LICENSE` for details.
